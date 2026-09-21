# Multi-Database Configuration Guide

## Overview

This guide explains how to configure and use multiple database connections in the Spring Boot application:
- **Primary (PostgreSQL)**: Main application database for orders, labels, shipments
- **Secondary (Oracle NDS)**: External data source for DTC orders from Oracle TB_SHIPX_DTC_UVW view

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                   │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────────┐          ┌──────────────────────┐     │
│  │ PostgreSQL       │          │ Oracle NDS           │     │
│  │ (Primary)        │          │ (Secondary - R/O)    │     │
│  │                  │          │                      │     │
│  │ • label_batch    │          │ • TB_SHIPX_DTC_UVW   │     │
│  │ • orders         │◄─────────┤   (DTC View)         │     │
│  │ • shipments      │  Sync    │ • OEHEAD, OEDETL     │     │
│  │ • tracking       │          │ • item_master        │     │
│  │ • clients        │          │ • wco_harmonized_code│     │
│  │                  │          │                      │     │
│  └──────────────────┘          └──────────────────────┘     │
│       ↑                              ↑                       │
│       │                              │                       │
│  EntityManager                   EntityManager              │
│  (Postgres TM)                   (Oracle TM)                │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  OracleDtcSyncService                                │   │
│  │  ├─ Fetch: OracleDtcOrderRepository                  │   │
│  │  ├─ Transform: OracleDtcOrder → Order                │   │
│  │  └─ Save: OrderRepository (Postgres)                 │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  DtcTimedBackgroundService (Scheduled)               │   │
│  │  ├─ Peak:   Every 5 min (12:00-21:59 weekdays)      │   │
│  │  ├─ Normal: Every 20 min (06:00-11:59)              │   │
│  │  └─ Off:    Every 30 min (22:00-05:59)              │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Configuration Files

### 1. application.properties

```properties
# ==========================================
# Primary Database - PostgreSQL
# ==========================================
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/multiship_db}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:root}
spring.datasource.driver-class-name=org.postgresql.Driver

# ==========================================
# Secondary Database - Oracle NDS
# ==========================================
spring.datasource.oracle.url=${ORACLE_DB_URL:jdbc:oracle:thin:@192.168.3.8:1521:tb10g}
spring.datasource.oracle.username=${ORACLE_DB_USERNAME:shipx_read}
spring.datasource.oracle.password=${ORACLE_DB_PASSWORD:password}
spring.datasource.oracle.driver-class-name=oracle.jdbc.driver.OracleDriver
spring.datasource.oracle.hikari.maximum-pool-size=${ORACLE_POOL_SIZE:10}
spring.datasource.oracle.hikari.connection-timeout=${ORACLE_CONNECTION_TIMEOUT_MS:10000}
```

### 2. Environment Variables

Set these in your deployment:

```bash
# PostgreSQL (Primary)
export DB_URL=jdbc:postgresql://postgres.internal:5432/multiship_prod
export DB_USERNAME=postgres
export DB_PASSWORD=<secure-password>
export DB_POOL_SIZE=50

# Oracle (Secondary)
export ORACLE_DB_URL=jdbc:oracle:thin:@192.168.3.8:1521:tb10g
export ORACLE_DB_USERNAME=shipx_read
export ORACLE_DB_PASSWORD=<oracle-password>
export ORACLE_POOL_SIZE=10
```

---

## Configuration Classes

### PostgresDataSourceConfig

- **Location**: `com.multiship.backend.config.PostgresDataSourceConfig`
- **Purpose**: Primary database configuration
- **Features**:
  - 50 max pool size
  - 10 min idle connections
  - 10s connection timeout
  - 30 min max lifetime
  - 5 min keepalive

### OracleDataSourceConfig

- **Location**: `com.multiship.backend.config.OracleDataSourceConfig`
- **Purpose**: Secondary database configuration
- **Features**:
  - 10 max pool size (conservative)
  - 5 min idle connections
  - Read-only access
  - 300s command timeout

---

## Data Models

### PostgreSQL Entities

```
com.multiship.backend.model.Order
├─ orderNo
├─ custNo
├─ shipvia
├─ shipName, shipAddr1..3
├─ weight, goodsDesc
└─ createdDate, isManual="B" (background)
```

### Oracle Entities

```
com.multiship.backend.model.oracle.OracleDtcOrder
├─ batchId (PK)
├─ toteNumber
├─ orderNo
├─ shipViaCode
├─ SHIP_NAME, SHIP_ADDR1..3
├─ WEIGHT, PRICE
├─ INTL_YN, THIRD_PARTY_ACC
└─ CREATED_DATE
```

---

## Repositories

### OracleDtcOrderRepository

Queries the Oracle `TB_SHIPX_DTC_UVW` view:

```java
// Fetch all pending DTC orders
List<OracleDtcOrder> findAllPendingDtcOrders();

// Fetch by tenant
List<OracleDtcOrder> findPendingOrdersByTenant(String tenantId);

// Count pending
long countPendingByTenant(String tenantId);
```

**Filters Applied**:
- `BATCH_ID != 0`
- `TOTE_NUMBER != 0`
- `ORDER_SUFFIX = '0'`
- Command timeout: **300 seconds**

---

## Services

### OracleDtcSyncService

**Main sync logic**:

```java
public OracleSyncResult syncPendingDtcOrders(String tenantId) {
    // 1. Fetch from Oracle (300s timeout)
    List<OracleDtcOrder> oracleOrders = 
        oracleDtcOrderRepository.findPendingDtcOrders(tenantId);
    
    // 2. Transform to PostgreSQL Order model
    // 3. Check for duplicates (idempotent via wmsExternalId)
    // 4. Bulk insert to PostgreSQL (ON CONFLICT DO NOTHING)
    // 5. Return sync result: {fetched, imported, skipped}
}
```

**Result**:

```java
OracleSyncResult {
    int fetched;     // Orders from Oracle
    int imported;    // New orders saved to Postgres
    int skipped;     // Orders already in Postgres
    String message;
}
```

### DtcTimedBackgroundService

**Automatic scheduling**:

```
Peak Hours (12:00-21:59 weekdays):   Every 5 minutes
Normal Hours (06:00-11:59):           Every 20 minutes
Off Hours (22:00-05:59):              Every 30 minutes
FedEx ETD (21:00 daily):              Once per day
```

**Cron Expressions**:

```java
@Scheduled(cron = "0 */5 12-21 * * MON-FRI")    // Peak
@Scheduled(cron = "0 */20 6-11 * * *")          // Normal
@Scheduled(cron = "0 */30 22-23,0-5 * * *")    // Off
@Scheduled(cron = "0 0 21 * * *")               // ETD
```

---

## REST Endpoints

### Manual DTC Sync

```
POST /api/v1/dtc/sync/oracle?tenantId=ACME
```

**Response**:

```json
{
  "status": "SUCCESS",
  "code": 200,
  "message": "DTC order sync completed",
  "data": {
    "fetched": 150,
    "imported": 120,
    "skipped": 30,
    "message": "Synced: imported=120, skipped=30, failed=0"
  }
}
```

### Check Pending Count

```
GET /api/v1/dtc/pending-count?tenantId=ACME
```

**Response**:

```json
{
  "status": "SUCCESS",
  "code": 200,
  "data": {
    "tenantId": "ACME",
    "pendingCount": 245
  }
}
```

---

## Data Flow

### Step 1: Fetch from Oracle

```
DTCTimedBackgroundService.syncDtcOrdersPeakHours()
  ↓
OracleDtcSyncService.syncPendingDtcOrders(tenantId)
  ↓
OracleDtcOrderRepository.findPendingDtcOrders(tenantId)
  ↓
SELECT * FROM TB_SHIPX_DTC_UVW
  WHERE BATCH_ID != 0
    AND TOTE_NUMBER != 0
    AND ORDER_SUFFIX = '0'
  [300s timeout]
```

### Step 2: Transform

```
OracleDtcOrder → Order
  orderNo       → orderNo
  custNo        → custNo
  shipViaCode   → shipvia
  SHIP_NAME     → shipName
  WEIGHT        → weight
  batchId       → wmsExternalId (dedup key)
```

### Step 3: Deduplicate & Save

```
Check: postgresOrderRepository.existsByWmsExternalId(batchId)
  ↓
If exists: skip (already imported)
  ↓
If not: save to PostgreSQL (idempotent via unique constraint)
  ↓
INSERT ... ON CONFLICT DO NOTHING
```

### Step 4: Result

```
OracleSyncResult {
    fetched: 150    (from Oracle)
    imported: 120   (new to PostgreSQL)
    skipped: 30     (already in PostgreSQL)
}
```

---

## Deployment Checklist

### Prerequisites

- [ ] Oracle JDBC driver in pom.xml: `com.oracle.database.jdbc:ojdbc11`
- [ ] Oracle database user with SELECT on TB_SHIPX_DTC_UVW
- [ ] Network connectivity to 192.168.3.8:1521 (Oracle)
- [ ] PostgreSQL running on primary database

### Configuration

- [ ] `ORACLE_DB_URL` set to your Oracle instance
- [ ] `ORACLE_DB_USERNAME` with read access
- [ ] `ORACLE_DB_PASSWORD` secure and set in secrets manager
- [ ] `ORACLE_POOL_SIZE` tuned for your workload (default: 10)

### Testing

- [ ] Start application with `-Dspring.profiles.active=dev`
- [ ] Verify both datasources initialized in logs
- [ ] Manual sync: `POST /api/v1/dtc/sync/oracle?tenantId=TEST`
- [ ] Check PostgreSQL for imported orders
- [ ] Monitor logs for sync errors

### Monitoring

- [ ] Set up alerts on OracleDtcSyncService log errors
- [ ] Track sync metrics: fetched, imported, skipped
- [ ] Monitor connection pool usage:
  - PostgreSQL: target 50, warn > 45
  - Oracle: target 10, warn > 8

---

## Troubleshooting

### Oracle Connection Fails

**Error**: `ORA-12514: TNS:listener does not currently know of service requested`

**Solution**:
```bash
# Verify Oracle service
sqlplus -l shipx_read/<password>@192.168.3.8:1521/tb10g

# Check ORACLE_DB_URL format
jdbc:oracle:thin:@192.168.3.8:1521:tb10g
```

### Slow Sync (> 5 min)

**Cause**: Large dataset or network latency

**Solution**:
1. Check Oracle query execution time:
   ```sql
   SELECT COUNT(*) FROM TB_SHIPX_DTC_UVW 
   WHERE BATCH_ID != 0 AND TOTE_NUMBER != 0;
   ```
2. Increase `ORACLE_POOL_SIZE` to 15–20
3. Increase command timeout: `CommandTimeout 600` (10 min)

### Orders Not Importing

**Check**:
1. Is OracleDtcSyncService scheduled? Look for `[DTC Peak]` logs
2. Are there pending orders in Oracle?
   ```sql
   SELECT COUNT(*) FROM TB_SHIPX_DTC_UVW 
   WHERE BATCH_ID != 0;
   ```
3. Are they being skipped as duplicates?
   ```sql
   SELECT COUNT(*) FROM label_batch 
   WHERE wms_external_id IS NOT NULL;
   ```

### Deadlock Between Databases

**Why**: Distributed transactions across two databases

**Solution**:
- Keep transactions simple and single-database
- PostgreSQL writes only (no circular updates)
- Oracle reads only (read-only EntityManager)
- If cross-DB transaction needed: use saga pattern (separate txns)

---

## Performance Tuning

### Oracle Pool Size

```
Peak hours (5-min sync):    Pool size = 10-15
Normal hours (20-min):      Pool size = 8-10
Off hours (30-min):         Pool size = 5-8
```

### Connection Lifetime

```properties
# Default: 30 min (causes NAT timeout)
spring.datasource.oracle.hikari.max-lifetime=1800000

# Keep-alive every 5 min (prevents idle close)
spring.datasource.oracle.hikari.keepalive-time=300000
```

### Query Optimization

```sql
-- Index on filter columns (DBA to verify)
CREATE INDEX idx_dtc_uvw_batch_tote 
ON TB_SHIPX_DTC_UVW(BATCH_ID, TOTE_NUMBER);
```

---

## Security Considerations

⚠️ **Important**:

1. **Never hardcode credentials** in code
2. **Rotate `ORACLE_DB_PASSWORD`** every 90 days
3. **Use read-only Oracle user** (no INSERT/UPDATE/DELETE)
4. **Encrypt connection strings** in transit
5. **Audit DTC order imports** for anomalies
6. **Limit PostgreSQL credentials** to application only

---

## Related Documents

- [ShipXSync Data-Flow Deep Dive](./shipxsync-data-flow.md) — Oracle architecture reference
- [Database Migration Guide](./DATABASE_MIGRATIONS.md) — PostgreSQL schema management
- [Deployment Guide](./DEPLOYMENT.md) — Production checklist


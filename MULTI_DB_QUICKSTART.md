# Multi-Database Setup - Quick Start

## What Was Created ✅

### Configuration Files
- ✅ `PostgresDataSourceConfig.java` — Primary PostgreSQL datasource
- ✅ `OracleDataSourceConfig.java` — Secondary Oracle datasource
- ✅ `application.properties` — Updated with Oracle config

### Models
- ✅ `OracleDtcOrder.java` — Maps Oracle TB_SHIPX_DTC_UVW view

### Repositories
- ✅ `OracleDtcOrderRepository.java` — Queries Oracle DTC view

### Services
- ✅ `OracleDtcSyncService.java` — Sync Oracle → PostgreSQL
- ✅ `DtcTimedBackgroundService.java` — Automatic scheduling (5/20/30 min)

### Controllers
- ✅ `OracleDtcSyncController.java` — REST endpoints for manual sync

### Documentation
- ✅ `docs/MULTI_DATABASE_SETUP.md` — Complete setup guide

---

## Quick Setup (5 minutes)

### 1. Set Environment Variables

```bash
# PostgreSQL (already configured)
export DB_URL=jdbc:postgresql://localhost:5432/multiship_db
export DB_USERNAME=postgres
export DB_PASSWORD=root

# Oracle NDS (ADD THESE)
export ORACLE_DB_URL=jdbc:oracle:thin:@192.168.3.8:1521:tb10g
export ORACLE_DB_USERNAME=shipx_read
export ORACLE_DB_PASSWORD=your_oracle_password
export ORACLE_POOL_SIZE=10
```

### 2. Build Application

```bash
cd backend
mvn clean install -DskipTests
```

### 3. Start Application

```bash
java -jar target/backend-0.0.1-SNAPSHOT.jar
```

### 4. Check Logs

Look for these successful initialization messages:

```
[INFO] Creating new datasource: postgresDataSource
[INFO] Creating new datasource: oracleDataSource
[INFO] Postgres EntityManagerFactory initialized
[INFO] Oracle EntityManagerFactory initialized
```

---

## Testing (Manual)

### Test 1: Fetch Pending Orders from Oracle

```bash
curl -X POST http://localhost:8080/api/v1/dtc/sync/oracle
```

**Expected Response**:
```json
{
  "status": "SUCCESS",
  "code": 200,
  "data": {
    "fetched": 150,
    "imported": 120,
    "skipped": 30,
    "message": "Synced: imported=120, skipped=30, failed=0"
  }
}
```

### Test 2: Check Pending Count

```bash
curl http://localhost:8080/api/v1/dtc/pending-count?tenantId=ACME
```

**Expected Response**:
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

### Test 3: Verify PostgreSQL Import

```sql
SELECT COUNT(*) FROM label_batch 
WHERE order_source = 'DTC' 
AND created_date > NOW() - INTERVAL '1 hour';
```

Should show recently imported DTC orders.

---

## Automatic Scheduling

Once started, the application automatically syncs DTC orders:

| Time Window | Interval | Frequency |
|---|---|---|
| 06:00–11:59 | Every 20 min | Normal hours |
| 12:00–21:59 (weekdays) | Every 5 min | **Peak hours** |
| 22:00–05:59 | Every 30 min | Off hours |

**Monitor in logs**:
```bash
tail -f logs/app.log | grep DTC
```

Output:
```
[2026-09-21 12:05:00] [DTC Peak] Starting DTC sync (5-min interval)
[2026-09-21 12:05:05] [DTC Peak] Completed: OracleSyncResult{fetched=45, imported=35, skipped=10...}
[2026-09-21 12:10:00] [DTC Peak] Starting DTC sync (5-min interval)
...
```

---

## File Locations

```
backend/
├── src/main/java/com/multiship/backend/
│   ├── config/
│   │   ├── PostgresDataSourceConfig.java     [PRIMARY DB]
│   │   └── OracleDataSourceConfig.java       [SECONDARY DB]
│   ├── model/
│   │   └── oracle/
│   │       └── OracleDtcOrder.java           [ORACLE VIEW MAPPING]
│   ├── repository/
│   │   └── oracle/
│   │       └── OracleDtcOrderRepository.java [ORACLE QUERIES]
│   ├── service/
│   │   └── oracle/
│   │       ├── OracleDtcSyncService.java     [SYNC LOGIC]
│   │       └── DtcTimedBackgroundService.java[SCHEDULER]
│   └── controller/
│       └── OracleDtcSyncController.java      [REST ENDPOINTS]
├── src/main/resources/
│   └── application.properties                [CONFIG]
└── pom.xml                                   [ORACLE DRIVER ADDED]

docs/
└── MULTI_DATABASE_SETUP.md                   [DETAILED GUIDE]
```

---

## Data Flow Diagram

```
Oracle NDS (Read-Only)
  ↓
  └─ TB_SHIPX_DTC_UVW
      └─ OracleDtcOrderRepository.findPendingDtcOrders()
          └─ OracleDtcSyncService.syncPendingDtcOrders()
              ├─ Fetch: List<OracleDtcOrder>
              ├─ Transform: OracleDtcOrder → Order
              ├─ Deduplicate: Check wms_external_id
              └─ Save: OrderRepository.save()
                  ↓
              PostgreSQL (Write)
                  ↓
              label_batch table
                  ├─ order_no
                  ├─ shipvia_cd
                  ├─ order_source = "DTC"
                  └─ created_date
```

---

## Troubleshooting

### Issue: "Connection refused" to Oracle

**Check**:
1. Is Oracle running on 192.168.3.8:1521?
   ```bash
   tnsping tb10g
   ```
2. Is `ORACLE_DB_URL` correct?
   ```bash
   echo $ORACLE_DB_URL
   ```
3. Does user exist with SELECT permission?

**Fix**:
```bash
sqlplus -l shipx_read/<password>@192.168.3.8:1521/tb10g
SELECT COUNT(*) FROM TB_SHIPX_DTC_UVW;
```

### Issue: Orders not importing

**Check logs**:
```bash
grep "OracleDtcSyncService" logs/app.log
```

**Verify Oracle has data**:
```sql
-- On Oracle
SELECT COUNT(*) FROM TB_SHIPX_DTC_UVW 
WHERE BATCH_ID != 0 AND TOTE_NUMBER != 0;
```

**Check PostgreSQL received them**:
```sql
-- On PostgreSQL
SELECT COUNT(*), order_source FROM label_batch 
GROUP BY order_source;
```

### Issue: Scheduled sync not running

**Check**:
1. Application logs show scheduler started?
   ```bash
   grep "Scheduling" logs/app.log
   ```
2. Are we in the right time window?
3. Any transaction errors?
   ```bash
   grep "ERROR.*DTC\|ERROR.*Oracle" logs/app.log
   ```

---

## Next Steps

1. ✅ **Deploy** to your environment
2. ✅ **Monitor** first sync in logs
3. ✅ **Verify** PostgreSQL receives orders
4. ✅ **Test** label generation on imported orders
5. ✅ **Setup alerts** on sync failures

---

## Support

For detailed configuration, see:
- **Full Setup Guide**: `docs/MULTI_DATABASE_SETUP.md`
- **ShipXSync Reference**: ShipXSync — Data-Flow Deep Dive document
- **Architecture**: Multi-database section above

---

## Summary Table

| Component | Type | Purpose |
|-----------|------|---------|
| PostgreSQL | Primary DB | Orders, labels, shipments |
| Oracle NDS | Secondary DB | DTC orders (read-only) |
| OracleDtcSyncService | Service | Sync orchestration |
| DtcTimedBackgroundService | Scheduler | Auto-sync every 5/20/30 min |
| OracleDtcSyncController | REST | Manual sync trigger |
| TB_SHIPX_DTC_UVW | Oracle View | 37-column pending orders |
| label_batch | Postgres Table | Imported DTC orders |


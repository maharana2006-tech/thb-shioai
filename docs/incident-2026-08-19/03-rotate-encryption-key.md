# 03 — Rotate `SECRETS_ENCRYPTION_KEY` + re-encrypt carrier credentials

## Why
`CryptoService` (backend/src/main/java/com/multiship/backend/config/CryptoService.java)
uses `SECRETS_ENCRYPTION_KEY` (env var, base64-encoded ≥32-byte key) as the
AES-GCM key for `carrier_account_ref.client_secret` and `.access_token`.
Those fields are stored encrypted-at-rest — but only if the attacker
does **not** know the encryption key.

If the leaked pg_dump contained a `system_settings` row holding the key,
OR if the key was pasted into `application.properties` and committed, OR
if a dev-env value happens to match prod, treat every ciphertext as
plaintext to whoever has the dump.

**When in doubt: assume compromised. Rotate.**

## Non-negotiable prep

1. **Do NOT delete the old key** until re-encryption is complete. The
   plan is decrypt-with-old → encrypt-with-new, per row.
2. **Snapshot the DB** before running the migration. Not a Flyway
   snapshot — a real pg_dump to secure offline storage. Rollback plan:
   restore + revert env if the re-encryption fails partway.
3. **Coordinate with 04-carrier-credential-rotation.md** — if you're
   rotating the actual carrier API keys anyway (recommended), you don't
   need to re-encrypt the old ones. Just save the new keys with the new
   `SECRETS_ENCRYPTION_KEY`.

## Sequence

### Step 1 — generate a new key
```bash
openssl rand -base64 32
# Example output: PKxK3Q7wMe9jQyKf5vX2Rc7bLp1nT4uY8Wg6HzE0AaQ=
```
Save it somewhere secure (password manager / secrets vault). You'll
need both the old and new keys during the migration.

### Step 2 — deploy a dual-key build (safest path)
Extend `CryptoService` to accept a comma-separated list of keys and
try each on decrypt (newest first for encrypt). Sketch:

```java
// In CryptoService.java — replace the single-key constructor.
private final SecretKey primary;               // encrypt with this
private final List<SecretKey> allForDecrypt;   // try each on decrypt

public CryptoService(@Value("${secrets.encryption-keys:${secrets.encryption-key:}}") String csvKeys) {
    List<SecretKey> keys = Arrays.stream(csvKeys.split(","))
            .map(String::trim).filter(s -> !s.isEmpty())
            .map(k -> new SecretKeySpec(Base64.getDecoder().decode(k), "AES"))
            .toList();
    if (keys.isEmpty()) throw new IllegalStateException("SECRETS_ENCRYPTION_KEY* not configured");
    this.primary = keys.get(0);
    this.allForDecrypt = keys;
}

public String decrypt(String base64Ciphertext) {
    Exception last = null;
    for (SecretKey k : allForDecrypt) {
        try { return decryptWith(k, base64Ciphertext); }
        catch (Exception e) { last = e; }  // wrong key → AEAD tag mismatch
    }
    throw new CryptoUnavailableException("all keys failed", last);
}
```

Deploy with `SECRETS_ENCRYPTION_KEYS=<NEW>,<OLD>` (comma-separated, new
first). All new writes use the new key; all existing ciphertext decrypts
via the old one.

### Step 3 — re-encrypt every existing row
Run this Java one-off after step 2 is deployed. Add as a Spring `@Component`
implementing `CommandLineRunner`, guarded behind a `--rotate-secrets`
CLI flag so it doesn't fire on every boot:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SecretsReencryptRunner implements CommandLineRunner {
    private final CarrierAccountRefRepository accountRepo;
    private final CryptoService crypto;

    @Override
    public void run(String... args) {
        if (Arrays.stream(args).noneMatch("--rotate-secrets"::equals)) return;
        log.warn("SECRETS_ENCRYPTION_KEY rotation — re-encrypting carrier_account_ref.");

        int touched = 0;
        for (CarrierAccountRef acct : accountRepo.findAll()) {
            boolean changed = false;
            if (acct.getClientSecret() != null) {
                acct.setClientSecret(crypto.encrypt(crypto.decrypt(acct.getClientSecret())));
                changed = true;
            }
            if (acct.getAccessToken() != null) {
                acct.setAccessToken(crypto.encrypt(crypto.decrypt(acct.getAccessToken())));
                changed = true;
            }
            if (changed) {
                accountRepo.save(acct);
                touched++;
            }
        }
        // Also do output_destination SFTP secrets (see OutputDestinationAdminService).
        // Also do external_webhook_subscription.secret_encrypted (WebhookSecretCipher).
        // Verify by grepping @Convert(converter = EncryptedStringConverter.class) in models/.

        log.warn("Rotation complete — {} carrier accounts re-encrypted. Now remove OLD from SECRETS_ENCRYPTION_KEYS and redeploy.", touched);
    }
}
```

Boot with:
```bash
SECRETS_ENCRYPTION_KEYS="<NEW>,<OLD>" java -jar app.jar --rotate-secrets
```

### Step 4 — drop the old key
Once step 3 completes cleanly and you've verified a sample decrypt with
the new key alone:
```bash
SECRETS_ENCRYPTION_KEYS=<NEW>  # drop the old
# Or the pre-rotation single-key form:
SECRETS_ENCRYPTION_KEY=<NEW>
```
Redeploy.

## Verify
```sql
-- Ciphertext length changes slightly (new IV per encrypt), so pre/post
-- lengths won't match. But nothing should be plaintext.
SELECT id, client_code,
       LEFT(client_secret, 8) AS secret_prefix,
       LENGTH(client_secret) AS secret_len
  FROM carrier_account_ref
 ORDER BY id;
```

Then manually confirm one carrier connect still works via the admin UI
(hits the decrypt path).

## Related files to check
Every field annotated `@Convert(converter = EncryptedStringConverter.class)`
in the codebase — `grep -rn EncryptedStringConverter backend/src/main/java`
lists them. As of this incident:
- `CarrierAccountRef.clientSecret` + `accessToken`
- `SystemSetting.value` (for SFTP secrets stored under `sftp.secret.*` keys)
- `ExternalWebhookSubscription.secretEncrypted` (post-#400)

The re-encrypt runner sketch above covers `CarrierAccountRef` only —
extend it to cover the other two tables the same way.

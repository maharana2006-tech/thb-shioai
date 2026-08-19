# 05 — User breach notification

## ⚠️ Legal-review-required
This is a starter template. Before sending:
- **Talk to counsel.** Jurisdiction-specific obligations vary (GDPR 72h,
  CA CCPA, NY SHIELD, HIPAA if health data, etc.). This template is a
  reasonable-effort disclosure but not a legal opinion.
- Confirm what personal data was actually in each user's rows before
  claiming "no PII beyond email/name" — the `saved_recipients`,
  `order_lines`, and `carrier_account_ref` tables may hold more.
- If your data-processing agreements with customers require notification
  under specific timelines, honour those on top of statutory ones.

## Timing
- **GDPR**: 72 hours from awareness of the breach for a supervisory-
  authority notification, and "without undue delay" for affected user
  notification when there's "high risk to rights and freedoms" — which
  a leaked password-hash arguably is.
- **CA CCPA**: notification "in the most expedient time possible and
  without unreasonable delay" (no fixed hour count, but "60 days" is a
  common upper bound before regulators start asking questions).

## Whom to notify
1. **Every user in the `users` table** that was in the dump (7 users
   per the pg_dump snapshot).
2. **Any customer tenant** whose data appeared in `clients`, `orders`,
   `order_lines`, `saved_recipients`, `order_customs_item` — even if
   they weren't the directly-affected users, their downstream data was.
3. **Data-protection authority** if GDPR applies (Ireland DPC / your
   lead supervisory authority).

## Template — user email

Subject: **Security notice: your ShipX account requires a password reset**

> Hi \<name\>,
>
> We're writing to let you know about a data-security incident that
> affects your ShipX account and to explain the actions we've taken
> and the action we need from you.
>
> **What happened**
>
> On \<date\>, we discovered that a set of database backup files was
> accidentally committed to our source-control repository, which is
> publicly accessible. Those files were removed as soon as we became
> aware, and we've begun the process of purging cached copies.
>
> **What was exposed**
>
> Your ShipX account data was in the backup files. Specifically:
>
> - Your username, email address, and full name
> - Your account's password stored as a bcrypt hash (not the plaintext
>   password — bcrypt is designed to make recovering the original
>   password from the hash computationally expensive)
> - Data about the shipments and clients associated with your account
>
> Your carrier-API credentials were also in the files. Although those
> are stored encrypted, we're rotating them as a precaution.
>
> **What we've done**
>
> - Removed the backup files from the repository.
> - Reset the password for every affected account (yours included) —
>   you can no longer sign in with your old password.
> - Bumped a session-invalidation counter so any browser session that
>   was open when we ran the reset is now signed out.
> - Rotated all carrier API credentials.
> - Rotated the at-rest encryption key that protected the credentials.
> - Requested that GitHub purge the cached copies of the files.
> - Added preventive controls so this specific mistake can't recur.
>
> **What you need to do**
>
> 1. Go to https://\<your-shipx-url\>/auth/password/forgot
> 2. Enter your email address (the one this message came to).
> 3. Follow the reset link we email you.
> 4. If you re-used your ShipX password on any other site, change it
>    there too. (Even though bcrypt is hard to reverse, we recommend
>    treating any re-used password as compromised.)
>
> **Timeline**
>
> - \<date the dumps were first committed\> — backup files added to
>   the repository (in a feature branch, not `main`).
> - \<date the merge happened\> — the feature branch was merged and
>   the files reached our `dev` branch.
> - \<date discovered\> — we discovered the exposure.
> - \<date within the same day\> — files removed from `dev`.
> - \<date\> — password reset and credential rotation completed.
>
> **How to reach us**
>
> If you have any questions or notice anything unusual about your
> account, reply to this email or contact security@\<your-domain\>.
>
> We're sorry this happened. We're taking steps to make sure it
> doesn't again.
>
> — The ShipX team

## Template — customer tenant notice

Subject: **Data-security notice concerning your \<tenant name\> data on ShipX**

> Hi \<tenant contact\>,
>
> We're writing to inform you of a data-security incident affecting
> data belonging to your organisation.
>
> On \<date\>, database backup files were accidentally committed to
> our public source-control repository. Those files included records
> from tables that reference your tenant, specifically:
>
> - Client records: \<count\> rows from `clients`
> - Shipments / order lines: \<counts\>
> - Recipient / customs data: \<counts\>
> - Carrier account identifiers: \<counts\>
>
> \<Include specifics from your DB — attach a data-subject inventory
> if applicable.\>
>
> **What we've done**
>
> \<Same "What we've done" list from the user template.\>
>
> **What we need from you**
>
> - Notify affected end-users under your privacy policy where required.
> - Let us know if you want us to help with any downstream investigation.
> - Confirm receipt of this notice.
>
> Under \<applicable law / DPA clause\>, we're obliged to inform you
> within \<time window\>. If you have questions, reply to this email
> or contact security@\<your-domain\>.
>
> — The ShipX team

## Template — supervisory authority (GDPR Art. 33)

Structure per your DPA's online form. Points to cover:
- Nature of the breach (accidental disclosure via source-control)
- Categories of data subjects (staff users of ShipX + downstream
  customer tenants + carrier account holders)
- Approximate number of data subjects (7 direct users + N tenant
  users + N recipient records)
- Categories of personal data (email, name, hashed password, address,
  phone, tracking metadata)
- Likely consequences (credential-stuffing risk, phishing risk against
  affected users)
- Measures taken (removal, password reset, key rotation, notification)
- DPO contact details

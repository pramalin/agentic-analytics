# merchant

One row per merchant on the platform.

- merchant_id: primary key, referenced by transaction.merchant_id.
- merchant_name: display name of the merchant. Free text, not an enum — no
  fixed casing convention to worry about here, unlike transaction.status.
- region_id: references region.region_id. Each merchant belongs to exactly one region.

# region

A small reference table of four US regions used to group merchants geographically.

- region_id: primary key, referenced by merchant.region_id.
- region_name: one of Northeast, Midwest, South, West. Title case, not uppercase
  (unlike transaction.status) — these are seeded exactly as written here.

To answer any question "by region" (e.g. declines by region, volume by region),
join transaction -> merchant -> region on merchant_id and region_id respectively.
There is no region_id directly on the transaction table — a common mistake is
writing `GROUP BY region` against the transaction table directly, which fails
because that column doesn't exist there.

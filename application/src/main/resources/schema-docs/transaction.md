# transaction

One row per card transaction attempt.

- transaction_id: primary key.
- merchant_id: references merchant.merchant_id. Every transaction belongs to exactly one merchant.
- transaction_ts: timestamp of the transaction attempt, in UTC.
- amount: transaction amount in USD.
- status: text column. Values are UPPERCASE, exactly one of: APPROVED, DECLINED,
  REVERSED. There is no lowercase or mixed-case variant — `status = 'declined'`
  matches zero rows; the correct filter is `status = 'DECLINED'`. Roughly 12% of
  transactions are DECLINED and 3% are REVERSED in the sample data; the rest are
  APPROVED.
- decline_reason: text column, only populated when status = 'DECLINED'. Also
  UPPERCASE, one of: INSUFFICIENT_FUNDS, SUSPECTED_FRAUD, CARD_EXPIRED,
  LIMIT_EXCEEDED. NULL for approved or reversed transactions.

Common questions this table answers: decline rates, decline reasons, transaction
volume and dollar amounts over time, and per-merchant or per-region performance
when joined with merchant and region.

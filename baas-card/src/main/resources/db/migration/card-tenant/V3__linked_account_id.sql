-- Stage 5 (DEF-1C-23): bind a card to the engine account it draws funds from.
-- Set at issuance and validated against the engine. Pre-existing rows remain null and
-- decline authorization with RC 78 until rebound.
ALTER TABLE cards ADD COLUMN IF NOT EXISTS linked_account_id UUID;

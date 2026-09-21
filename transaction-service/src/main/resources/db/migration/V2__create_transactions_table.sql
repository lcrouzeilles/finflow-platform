CREATE TABLE transactions (
                              id UUID PRIMARY KEY,
                              source_account_id UUID NOT NULL,
                              destination_account_id UUID NOT NULL,
                              amount NUMERIC(19, 4) NOT NULL,
                              currency VARCHAR(3) NOT NULL,
                              status VARCHAR(20) NOT NULL,
                              created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
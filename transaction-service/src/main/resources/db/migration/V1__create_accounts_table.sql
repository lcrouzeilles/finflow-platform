CREATE TABLE accounts (
                          id UUID PRIMARY KEY,
                          account_number VARCHAR(100) NOT NULL UNIQUE,
                          balance NUMERIC(19, 4) NOT NULL,
                          currency VARCHAR(3) NOT NULL,
                          status VARCHAR(20) NOT NULL
);
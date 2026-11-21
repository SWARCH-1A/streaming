package main

import "testing"

func TestVerifiedDatabaseURL(t *testing.T) {
	for _, address := range []string{
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=verify-full",
		"postgresql://audit_reader:fixture@audit-db/audit?sslmode=verify-full&sslrootcert=/fixture/ca.crt",
	} {
		if !verifiedDatabaseURL(address) {
			t.Fatal("valid verified connection rejected", address)
		}
	}
	for _, address := range []string{
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=disable&application_name=sslmode=verify-full",
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=disable&sslmode=verify-full",
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=verify-full&sslmode=disable",
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=verify-full-invalid",
		"postgres://audit_writer:fixture@audit-db/audit?sslmode=verify-full&bad=%xx",
		"postgres://audit_writer:fixture@audit-db/audit",
		"https://audit-db/audit?sslmode=verify-full",
	} {
		if verifiedDatabaseURL(address) {
			t.Fatal("unverified database configuration accepted", address)
		}
	}
}

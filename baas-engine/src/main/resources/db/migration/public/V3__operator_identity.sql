-- Operator identity: map a partner's Keycloak realm issuer to its organization.
ALTER TABLE public.partner_organizations
    ADD COLUMN keycloak_issuer VARCHAR(500);

CREATE UNIQUE INDEX ux_partner_org_keycloak_issuer
    ON public.partner_organizations (keycloak_issuer)
    WHERE keycloak_issuer IS NOT NULL;

CREATE TABLE catalog.buildings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL REFERENCES institution.colleges (id),
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    CONSTRAINT uq_buildings_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE catalog.rooms (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES institution.colleges (id),
    building_id     UUID NOT NULL REFERENCES catalog.buildings (id),
    code            TEXT NOT NULL,
    capacity        INT NOT NULL CHECK (capacity > 0),
    features_json   JSONB NULL,
    CONSTRAINT uq_rooms_building_code UNIQUE (building_id, code)
);

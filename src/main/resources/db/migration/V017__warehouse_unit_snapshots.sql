ALTER TABLE product_units
    ADD COLUMN warehouse_scope BIGINT UNSIGNED GENERATED ALWAYS AS (IFNULL(warehouse_id, 0)) STORED,
    ADD UNIQUE KEY uq_product_unit_warehouse (product_id, warehouse_scope, name);

ALTER TABLE product_units DROP INDEX product_id;

ALTER TABLE conversion_snapshots
    ADD COLUMN warehouse_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_conversion_snapshot_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

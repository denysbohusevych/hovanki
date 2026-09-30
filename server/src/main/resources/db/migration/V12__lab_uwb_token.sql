-- The radio lab's UWB tokens (docs/radar-run.md step 5.3, ADR 0017 §2.3 `uwb.ni`): a device of a run posts its
-- Nearby Interaction discovery token (NIDiscoveryToken archived, base64) and the run's other phones read it to range
-- with it. Opaque, not personal, only of the lab's test phones; it goes with the device and the run (ON DELETE
-- CASCADE of lab_devices) and is never in a log.
ALTER TABLE lab_devices ADD COLUMN uwb_token text;

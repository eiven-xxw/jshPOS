# POS Device Adapter

This private Flutter plugin is the only supported bridge between the POS app
and Android/vendor hardware APIs. The public Dart boundary is
`PosDeviceGateway`; the method channel and vendor SDK implementations stay
private to this package.

T0 implements contract negotiation, device metadata, an extensible capability
set, Kotlin registration, and deterministic tests. It intentionally does not
implement printing, scanning, scales, cash drawers, customer displays, or
vendor SDK bindings.

The base Android implementation advertises no hardware capabilities. A device
model may only advertise a capability after its adapter passes the hardware
certification suite.

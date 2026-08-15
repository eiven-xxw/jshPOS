# Device adapter rules

- Keep the public Flutter contract vendor-neutral and backward compatible.
- Keep `MethodChannel` usage under `lib/src`; application and feature code use
  `PosDeviceGateway` only.
- Never report a hardware capability until the implementation and certified
  device matrix tests pass.
- Do not commit vendor SDK binaries without provenance, license review, checksum,
  and an ADR.
- Native failures must use stable error codes and must not expose secrets or raw
  payment data.

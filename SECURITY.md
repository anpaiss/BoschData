# Security Policy

## Supported versions

Only the latest release is supported. Please update to the most recent version
before reporting an issue.

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report them privately through GitHub's private vulnerability reporting:
open the **Security** tab of this repository and click **Report a vulnerability**.

You can expect an acknowledgement within a few days. Fixes are published as a
new release; the report is disclosed publicly only after a fix is available.

## Scope

This is a Hammerhead Karoo extension. It runs on the device and uses no
network at all. It uses Bluetooth Low Energy in one way only: the Karoo
advertises as a Bosch Live Data Interface accessory, the e-bike the rider
paired from the Bosch Flow app connects to it, and the extension reads the
bike's live data. Nothing is written to the bike, nothing is collected or
transmitted. Reports about the extension's own code (e.g. exported Android
components, the GATT server, the handling of the bike's messages) are in
scope; issues in the Karoo OS, the SDK or the Bosch system should be reported
to Hammerhead or Bosch.

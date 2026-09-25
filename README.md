# Emporia Vue 2 for Hubitat

 ̶T̶h̶i̶s̶ ̶s̶c̶r̶i̶p̶t̶ ̶r̶e̶q̶u̶i̶r̶e̶s̶ ̶y̶o̶u̶ ̶t̶o̶ ̶a̶d̶d̶ ̶a̶n̶ ̶a̶u̶t̶h̶e̶n̶t̶i̶c̶a̶t̶i̶o̶n̶ ̶t̶o̶k̶e̶n̶.̶ ̶I̶ ̶a̶m̶ ̶u̶s̶i̶n̶g̶ ̶P̶y̶E̶m̶V̶u̶e̶ ̶t̶o̶ ̶g̶e̶n̶e̶r̶a̶t̶e̶ ̶t̶h̶e̶ ̶t̶o̶k̶e̶n̶,̶ ̶r̶u̶n̶n̶i̶n̶g̶ ̶o̶n̶ ̶a̶ ̶l̶i̶n̶u̶x̶ ̶m̶a̶c̶h̶i̶n̶e̶ ̶o̶n̶ ̶m̶y̶ ̶l̶o̶c̶a̶l̶ ̶n̶e̶t̶w̶o̶r̶k̶.̶ ̶Y̶o̶u̶ ̶c̶a̶n̶ ̶p̶a̶s̶s̶ ̶t̶h̶e̶ ̶t̶o̶k̶e̶n̶ ̶i̶n̶t̶o̶ ̶H̶u̶b̶i̶t̶a̶t̶ ̶u̶s̶i̶n̶g̶ ̶t̶h̶e̶ ̶n̶a̶t̶i̶v̶e̶ ̶M̶a̶k̶e̶r̶ ̶A̶P̶I̶ ̶a̶p̶p̶.̶
̶h̶t̶t̶p̶s̶:̶/̶/̶p̶y̶p̶i̶.̶o̶r̶g̶/̶p̶r̶o̶j̶e̶c̶t̶/̶p̶y̶e̶m̶v̶u̶e̶/̶
̶
̶I̶f̶ ̶a̶n̶y̶b̶o̶d̶y̶ ̶k̶n̶o̶w̶s̶ ̶h̶o̶w̶ ̶t̶o̶ ̶a̶u̶t̶h̶e̶n̶t̶i̶c̶a̶t̶e̶ ̶t̶o̶ ̶A̶W̶S̶ ̶u̶s̶i̶n̶g̶ ̶g̶r̶o̶o̶v̶y̶,̶ ̶I̶ ̶w̶o̶u̶l̶d̶ ̶b̶e̶ ̶h̶a̶p̶p̶y̶ ̶t̶o̶ ̶e̶m̶b̶e̶d̶ ̶t̶h̶e̶ ̶a̶u̶t̶h̶e̶n̶t̶i̶c̶a̶t̶i̶o̶n̶ ̶i̶n̶t̶o̶ ̶t̶h̶i̶s̶ ̶s̶c̶r̶i̶p̶t̶.̶

## New: Emporia Vue Integration app (beta)

Version 3 moves from a single virtual-device driver to a Hubitat **app** with child devices.

What changes:
- **Guided setup**: enter your Emporia email and password, then pick circuits from a list. You no longer run Generate Token or Get Device GID by hand.
- **Automatic sign-in**: tokens refresh themselves, and the app signs in again if the refresh token expires.
- **Non-blocking polling**: Emporia requests run in the background (async HTTP), so a slow API response doesn't hold up the hub.
- **Current draw**: `power` (W) and `energy` (kW) both show the latest 1-minute reading. `energy` is power in kW, like the 2.x driver, not a running kWh total.
- **Stable device IDs**: each child is keyed by `deviceGid-channelNum`, so renaming a circuit in the Emporia app doesn't create a duplicate. Circuits with the same name on different monitors don't collide.
- **One source of API calls**: only the app contacts Emporia. Child devices just receive the readings. Use the app's *Refresh now* button for an immediate update.
- **Balance channel** and an optional **account total** device.

Install (manual, until the package manifest is updated):
1. *Drivers Code*: add `emporia-child.groovy`.
2. *Apps Code*: add `emporia-app.groovy`.
3. *Apps* → *Add User App* → **Emporia Vue Integration**.

The legacy driver below still works. Existing installs are not changed.

## Legacy driver (2.x)

Special thanks to @amithalp for figuring out how to authenticate directly from Hubitat. The driver no longer requires an external script.


Start by creating a virtual device and setting the emporia driver. Fill in the required information in Preferences, then use the Generate Token action.

Once you have the token, you can retrieve the device GIDs. After that you can use Refresh to get the individual devices. It will auto-refresh based on your preferences.

This script will create child devices for each channel you have in Emporia. 

Multiple Emporia devices under one account is supported. The power total on the parent device will be the combined total of all devices.


This is not an official app for Emporia and the API may stop working at any time.

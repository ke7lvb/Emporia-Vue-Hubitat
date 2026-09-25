/**
 *  Emporia Vue Child Device
 *
 *  One Emporia circuit (or the account total). Created by the "Emporia Vue Integration" app.
 */
metadata {
    definition(
        name: "Emporia Vue Child Device",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia-child.groovy",
    ){
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "Refresh"
        capability "Sensor"

        attribute "lastUpdate", "string"
    }
}

def installed() {
    log.info "${device.displayName} installed"
}

def refresh() {
    parent?.componentRefresh(device)
}

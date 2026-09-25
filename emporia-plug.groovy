/**
 *  Emporia Smart Plug
 *
 *  An Emporia smart outlet with on/off control and power/energy reporting.
 *  Created by the "Emporia Vue Integration" app.
 */
metadata {
    definition(
        name: "Emporia Smart Plug",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia-plug.groovy",
    ){
        capability "Actuator"
        capability "Outlet"
        capability "Switch"
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

def on() {
    parent?.setOutlet(device, true)
}

def off() {
    parent?.setOutlet(device, false)
}

def refresh() {
    parent?.componentRefresh(device)
}

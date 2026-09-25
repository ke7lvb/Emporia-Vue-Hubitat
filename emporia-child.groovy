metadata {
    definition(
        name: "Emporia Vue Child Device",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia-child.groovy",
    ){
        capability "PowerMeter"
        capability "EnergyMeter"

        attribute "lastUpdate", "string"
    }
    preferences {

    }
}

def installed() {
    log.info "Driver installed"
}

def uninstalled() {
    log.info "Driver uninstalled"
}

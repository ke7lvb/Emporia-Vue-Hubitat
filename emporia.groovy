import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String API_HOST = "https://api.emporiaenergy.com"
@Field static final String AUTH_HOST = "https://cognito-idp.us-east-2.amazonaws.com/"
@Field static final String CLIENT_ID = "4qte47jbstod8apnfic0bunmrq"
@Field static final String MAINS = "1,2,3"
// Emporia reports kWh used over the scale interval; multiply by this to get average watts.
@Field static final Map WATTS_PER_KWH = ["1S": 3600000, "1MIN": 60000, "1H": 1000]

metadata {
    definition(
        name: "Emporia Vue Driver 2.x",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia.groovy",
    ){
        capability "Refresh"
        capability "PowerSource"
        capability "PowerMeter"
        capability "EnergyMeter"

        command "getDeviceGid"
        command "generateToken"
        command "refreshToken"

        attribute "lastUpdate", "string"
        attribute "tokenExpiry", "string"
        attribute "unixLastUpdate", "number"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: false
        input name: "jsonState", type: "bool", title: "Show JSON state", defaultValue: false
        input name: "email", type: "string", title: "Emporia Email", required: true
        input name: "password", type: "password", title: "Emporia Password", required: true
        input("scale", "enum", title: "Average power over", options: ["1S": "1 second", "1MIN": "1 minute", "1H": "1 hour"], required: true, defaultValue: "1H")
        input("refresh_interval", "enum", title: "How often to refresh the Emporia data", options: [
            0: "Do NOT update",
            1: "1 Minute",
            5: "5 Minutes",
            10: "10 Minutes",
            15: "15 Minutes",
            20: "20 Minutes",
            30: "30 Minutes",
            45: "45 Minutes",
            60: "1 Hour"
        ], required: true, defaultValue: "60")
    }
}

def version() { return "2.5.0" }

def installed() {
    if (logEnable) log.info "Driver installed"
    state.version = version()
    state.deviceGID = []
}

def uninstalled() {
    unschedule()
    if (logEnable) log.info "Driver uninstalled"
}

def updated() {
    if (logEnable) log.info "Settings updated"
    unschedule()   // also clears the token-refresh timer used by versions before 2.5

    def interval = (settings.refresh_interval ?: "60") as String
    if (interval == "60") {
        schedule("7 0 * ? * *", "refresh")
    } else if (interval != "0") {
        schedule("7 */${interval} * ? * *", "refresh")
    }

    // Sign in again if the account changed
    if (state.tokenEmail && state.tokenEmail != settings.email) {
        state.remove("idToken")
        state.remove("refreshToken")
        state.deviceGID = []
    }

    // Tidy state left by earlier versions
    ["accessToken", "deviceNames", "lastTokenUpdate"].each { state.remove(it) }
    if (!jsonState) state.remove("JSON")
    state.version = version()
}

/* ---------------------------------------------------------------- authentication */

private Map cognito(String flow, Map authParams) {
    def params = [
        uri: AUTH_HOST,
        headers: ["Content-Type": "application/x-amz-json-1.1",
                  "X-Amz-Target": "AWSCognitoIdentityProviderService.InitiateAuth"],
        body: JsonOutput.toJson([AuthFlow: flow, ClientId: CLIENT_ID, AuthParameters: authParams]),
        timeout: 20
    ]
    def result = null
    try {
        httpPost(params) { resp ->
            def data = resp.data
            if (data instanceof InputStream) data = new JsonSlurper().parse(data)
            else if (data instanceof String) data = new JsonSlurper().parseText(data)
            result = data?.AuthenticationResult
            if (!result) log.error "${flow}: AuthenticationResult missing in response"
        }
    } catch (e) {
        log.error "${flow} failed: ${e.message}"
    }
    if (!result) return null

    state.idToken = result.IdToken
    if (result.RefreshToken) state.refreshToken = result.RefreshToken
    state.tokenExpiry = now() + ((result.ExpiresIn ?: 3600) as Long) * 1000
    state.tokenEmail = settings.email
    sendEvent(name: "tokenExpiry", value: new Date(state.tokenExpiry as Long).format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")))
    return result
}

def generateToken() {
    if (cognito("USER_PASSWORD_AUTH", [USERNAME: settings.email, PASSWORD: settings.password])) {
        if (logEnable) log.info "Signed in to Emporia"
        return true
    }
    return false
}

def refreshToken() {
    if (state.refreshToken && cognito("REFRESH_TOKEN_AUTH", [REFRESH_TOKEN: state.refreshToken])) {
        if (debugLog) log.debug "Token refreshed"
        return true
    }
    return false
}

/** Makes sure a valid token is available: reuse it, refresh it, or sign in again. */
boolean ensureToken() {
    if (state.idToken && (state.tokenExpiry ?: 0) > now() + 300000) return true
    return refreshToken() || generateToken()
}

private Map apiParams(String path, Map query = null) {
    def p = [uri: API_HOST, path: path, headers: [authtoken: state.idToken], contentType: "application/json", timeout: 30]
    if (query) p.query = query
    return p
}

/* ---------------------------------------------------------------- devices */

def getDeviceGid() {
    if (!ensureToken()) {
        log.error "Unable to sign in to Emporia; check your email and password"
        return
    }
    try {
        httpGet(apiParams("/customers/devices")) { resp ->
            state.deviceGID = resp.data?.devices?.collect { it.deviceGid } ?: []
        }
        if (logEnable) log.info "Found Emporia devices: ${state.deviceGID}"
    } catch (e) {
        log.error "Error fetching device GID: ${e.message}"
    }
}

/* ---------------------------------------------------------------- polling */

def refresh() {
    if (!ensureToken()) {
        log.error "Unable to sign in to Emporia; check your email and password"
        return
    }
    if (!state.deviceGID) getDeviceGid()
    if (!state.deviceGID) {
        log.error "No Emporia devices found on this account"
        return
    }

    def query = [
        apiMethod: "getDeviceListUsages",
        deviceGids: state.deviceGID.join("+"),
        instant: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")),
        scale: activeScale(),
        energyUnit: "KilowattHours"
    ]
    if (debugLog) log.debug "Requesting usage: ${query}"
    asynchttpGet("handleUsage", apiParams("/AppAPI", query))
}

private String activeScale() {
    return WATTS_PER_KWH.containsKey(settings.scale) ? settings.scale : "1H"
}

def handleUsage(resp, data) {
    if (resp.status == 401) {
        log.warn "Emporia rejected the token; will sign in again on the next refresh"
        state.tokenExpiry = 0
        return
    }
    if (resp.hasError()) {
        log.error "Error during refresh (${resp.status}): ${resp.getErrorMessage()}"
        return
    }
    if (jsonState) state.JSON = resp.data

    def multiplier = WATTS_PER_KWH[activeScale()]
    BigDecimal combinedTotals = 0

    resp.json?.deviceListUsages?.devices?.each { dev ->
        dev.channelUsages?.each { cu ->
            if (debugLog) log.debug cu
            if (cu.usage == null) return   // monitor offline or interval not reported yet
            BigDecimal watts = ((cu.usage as BigDecimal) * multiplier).setScale(0, java.math.RoundingMode.HALF_UP)
            if (cu.channelNum == MAINS) combinedTotals += watts

            def cd = fetchChild(cu)
            cd?.sendEvent(name: "power", value: watts, unit: "W")
            cd?.sendEvent(name: "energy", value: watts / 1000, unit: "kW")
        }
    }

    sendEvent(name: "power", value: combinedTotals, unit: "W")
    sendEvent(name: "energy", value: combinedTotals / 1000, unit: "kW")
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'"))
    sendEvent(name: "unixLastUpdate", value: now())
}

/**
 * Children are keyed "<deviceGid>-<channelNum>" so renaming a circuit in Emporia, or two monitors
 * having circuits with the same name, doesn't create or merge devices. Children created by versions
 * before 2.5 were keyed by circuit name; those are re-keyed in place, so rules and dashboards that
 * use them keep working.
 */
def fetchChild(cu) {
    String dni = "${cu.deviceGid}-${cu.channelNum}"
    def cd = getChildDevice(dni)
    if (cd) return cd

    String legacyDni = cu.name in ["Main", "TotalUsage", "Balance"] ? "${cu.name}_${cu.deviceGid}" : cu.name
    cd = legacyDni ? getChildDevice(legacyDni) : null
    if (cd) {
        try {
            cd.setDeviceNetworkId(dni)
            if (logEnable) log.info "Migrated ${cd.displayName} from '${legacyDni}' to '${dni}'"
        } catch (e) {
            log.warn "Could not migrate ${cd.displayName} to '${dni}': ${e.message}"
        }
        return cd
    }

    String name = cu.name ?: "Channel ${cu.channelNum}"
    try {
        cd = addChildDevice("hubitat", "Generic Component Power Meter", dni, [name: name, isComponent: false])
        if (logEnable) log.info "Created child device ${name}"
    } catch (e) {
        log.error "Could not create child device ${name}: ${e.message}"
    }
    return cd
}

/** Refresh on a child device lands here. Children never call Emporia; only the parent refresh does. */
def componentRefresh(cd) {
    if (logEnable) log.info "${cd.displayName} is updated by ${device.displayName}; use Refresh on the parent device"
}

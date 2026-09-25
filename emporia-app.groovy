/**
 *  Emporia Vue Integration (Hubitat App)
 *
 *  Replaces the single virtual-device driver with an app that:
 *   - handles login / token refresh automatically (no manual "Generate Token")
 *   - discovers Vue monitors and circuits and lets you pick which to add
 *   - polls asynchronously so the hub is never blocked waiting on Emporia
 *   - reports current power as W ("power") and kW ("energy")
 *
 *  The app is the only thing that talks to Emporia; child devices just receive the data.
 *
 *  This is not an official Emporia integration; the cloud API may change at any time.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String API_HOST = "https://api.emporiaenergy.com"
@Field static final String AUTH_HOST = "https://cognito-idp.us-east-2.amazonaws.com/"
@Field static final String CLIENT_ID = "4qte47jbstod8apnfic0bunmrq"
@Field static final String NAMESPACE = "ke7lvb"
@Field static final String CHANNEL_DRIVER = "Emporia Vue Child Device"
@Field static final String TOTAL_DNI = "emporia-total"
@Field static final String MAINS = "1,2,3"

definition(
    name: "Emporia Vue Integration",
    namespace: NAMESPACE,
    author: "Ryan Lundell",
    description: "Energy monitoring for Emporia Vue",
    category: "Green Living",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/ke7lvb/Emporia-Vue-Hubitat/refs/heads/main/emporia-app.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
}

def version() { return "3.0.0-beta" }

/* ---------------------------------------------------------------- UI pages */

def mainPage() {
    dynamicPage(name: "mainPage", title: "Emporia Vue Integration ${version()}", nextPage: "devicesPage", install: false, uninstall: true) {
        section("Emporia account") {
            input "email", "text", title: "Email", required: true, submitOnChange: true
            input "password", "password", title: "Password", required: true, submitOnChange: true
            if (settings.email && settings.password) {
                paragraph login() ? "<b>Signed in.</b> Continue to pick devices." : "<b style='color:red'>Sign-in failed.</b> Check your credentials and the logs."
            }
        }
        section("Polling") {
            input "pollInterval", "enum", title: "Refresh every", defaultValue: "1", required: true, options: [
                "0": "Never (manual refresh only)", "1": "1 minute", "5": "5 minutes", "10": "10 minutes",
                "15": "15 minutes", "30": "30 minutes", "60": "1 hour"
            ]
        }
        if (app.installationState == "COMPLETE") {
            section { input "refreshNow", "button", title: "Refresh now" }
        }
        section("Logging") {
            input "logEnable", "bool", title: "Enable info logging", defaultValue: true
            input "debugLog", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def devicesPage() {
    def found = discover()
    dynamicPage(name: "devicesPage", title: "Choose devices", install: true, uninstall: true) {
        if (!found) {
            section { paragraph "No Emporia devices found. Go back and check your sign-in." }
            return
        }
        section("Circuits") {
            input "selected", "enum", title: "Create Hubitat devices for", multiple: true, required: false,
                options: found.collectEntries { k, v -> [(k): v.label] }.sort { it.value }
            input "createTotal", "bool", title: "Also create an account-total device (sum of all Vue mains)", defaultValue: true
        }
        section {
            paragraph "Unselecting an item removes its Hubitat device when you press Done."
        }
    }
}

/* ---------------------------------------------------------------- lifecycle */

def installed() { initialize() }

def updated() {
    unschedule()
    initialize()
}

def uninstalled() {
    unschedule()
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    state.version = version()
    syncChildDevices()

    def interval = (settings.pollInterval ?: "1") as String
    switch (interval) {
        case "0": break
        case "1": runEvery1Minute("poll"); break
        case "60": schedule("7 0 * ? * *", "poll"); break
        default: schedule("7 */${interval} * ? * *", "poll")
    }
    runIn(2, "poll")
}

def syncChildDevices() {
    def catalog = state.catalog ?: [:]
    def wanted = (settings.selected ?: []) as Set

    wanted.each { key ->
        def info = catalog[key]
        if (!info || getChildDevice(key)) return
        try {
            addChildDevice(NAMESPACE, CHANNEL_DRIVER, key, [name: CHANNEL_DRIVER, label: info.label, isComponent: false])
            if (logEnable) log.info "Created ${info.label}"
        } catch (e) {
            log.error "Could not create ${info.label}: is the '${CHANNEL_DRIVER}' driver installed? ${e.message}"
        }
    }

    if (settings.createTotal != false && !getChildDevice(TOTAL_DNI)) {
        addChildDevice(NAMESPACE, CHANNEL_DRIVER, TOTAL_DNI, [name: CHANNEL_DRIVER, label: "Emporia Total", isComponent: false])
    }

    getChildDevices().each { cd ->
        def dni = cd.deviceNetworkId
        def keep = dni == TOTAL_DNI ? settings.createTotal != false : wanted.contains(dni)
        if (!keep) {
            if (logEnable) log.info "Removing ${cd.displayName}"
            deleteChildDevice(dni)
        }
    }
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
        }
    } catch (e) {
        log.warn "Emporia ${flow} failed: ${e.message}"
    }
    if (!result) return null
    state.idToken = result.IdToken
    if (result.RefreshToken) state.refreshToken = result.RefreshToken
    state.tokenExpiry = now() + ((result.ExpiresIn ?: 3600) as Long) * 1000
    if (debugLog) log.debug "Token (${flow}) valid until ${new Date(state.tokenExpiry as Long)}"
    return result
}

boolean login() {
    return cognito("USER_PASSWORD_AUTH", [USERNAME: settings.email, PASSWORD: settings.password]) != null
}

/** Returns true when a usable ID token is available, refreshing or re-logging in as needed. */
boolean ensureToken() {
    if (state.idToken && (state.tokenExpiry ?: 0) > now() + 300000) return true
    if (state.refreshToken && cognito("REFRESH_TOKEN_AUTH", [REFRESH_TOKEN: state.refreshToken])) return true
    return login()
}

private Map apiParams(String path, Map query = null) {
    def p = [uri: API_HOST, path: path, headers: [authtoken: state.idToken], contentType: "application/json", timeout: 30]
    if (query) p.query = query
    return p
}

/* ---------------------------------------------------------------- discovery */

/** Fetches the device tree and returns key -> [label, gid, channelNum, top]. */
Map discover() {
    if (!ensureToken()) return [:]
    def catalog = [:]
    try {
        httpGet(apiParams("/customers/devices")) { resp ->
            resp.data?.devices?.each { addToCatalog(catalog, it, null) }
        }
    } catch (e) {
        log.error "Device discovery failed: ${e.message}"
    }
    state.catalog = catalog
    return catalog
}

private void addToCatalog(Map catalog, Map dev, String parentName) {
    def gid = dev.deviceGid
    def name = dev.locationProperties?.deviceName ?: (parentName ? "${parentName} device ${gid}" : "Emporia ${gid}")

    boolean top = parentName == null
    def channels = dev.channels ?: []
    channels.each { ch ->
        def num = ch.channelNum as String
        def chName = ch.name ?: (num == MAINS ? "Mains" : "Channel ${num}")
        catalog["${gid}-${num}"] = [label: "${name} - ${chName}", gid: gid, channelNum: num, top: top]
    }
    if (channels.size() > 1 && channels.any { it.channelNum == MAINS }) {
        catalog["${gid}-Balance"] = [label: "${name} - Balance (unmonitored)", gid: gid, channelNum: "Balance", top: top]
    }
    dev.devices?.each { addToCatalog(catalog, it, name) }
}

/* ---------------------------------------------------------------- polling */

def poll() {
    if (!getChildDevices()) return
    if (!ensureToken()) {
        log.error "Unable to authenticate with Emporia; skipping refresh"
        return
    }
    // Only top-level monitors are queried; anything nested under them arrives in nestedDevices.
    def gids = (state.catalog ?: [:]).values().findAll { it.top }*.gid.unique()
    if (!gids) return

    def instant = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
    def query = [apiMethod: "getDeviceListUsages", deviceGids: gids.join("+"), instant: instant,
                 scale: "1MIN", energyUnit: "KilowattHours"]
    asynchttpGet("handleUsage", apiParams("/AppAPI", query))
}

def handleUsage(resp, data) {
    if (!responseOk(resp, "usage")) return
    def usages = resp.json?.deviceListUsages?.devices ?: []
    BigDecimal total = 0
    String stamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))

    walkUsage(usages, 0) { cu, depth ->
        if (cu.usage == null) return      // device offline or minute not yet reported
        String key = "${cu.deviceGid}-${cu.channelNum}"
        BigDecimal kWh = cu.usage as BigDecimal
        if (depth == 0 && cu.channelNum == MAINS) total += kWh

        def cd = getChildDevice(key)
        if (cd) publish(cd, kWh, stamp)
    }

    def totalDev = getChildDevice(TOTAL_DNI)
    if (totalDev) publish(totalDev, total, stamp)
}

private void walkUsage(List devices, int depth, Closure visit) {
    devices.each { dev ->
        dev.channelUsages?.each { cu ->
            visit(cu, depth)
            if (cu.nestedDevices) walkUsage(cu.nestedDevices, depth + 1, visit)
        }
    }
}

private void publish(cd, BigDecimal kWh, String stamp) {
    // kWh used over the last minute -> average draw during that minute
    BigDecimal watts = (kWh * 60000).setScale(0, java.math.RoundingMode.HALF_UP)
    cd.sendEvent(name: "power", value: watts, unit: "W")
    cd.sendEvent(name: "energy", value: (watts / 1000).setScale(3, java.math.RoundingMode.HALF_UP), unit: "kW")
    cd.sendEvent(name: "lastUpdate", value: stamp)
}

private boolean responseOk(resp, String what) {
    if (resp.status == 401) {
        log.warn "Emporia rejected the token while fetching ${what}; will re-authenticate next poll"
        state.tokenExpiry = 0
        return false
    }
    if (resp.hasError()) {
        log.error "Emporia ${what} request failed (${resp.status}): ${resp.getErrorMessage()}"
        return false
    }
    return true
}

/* ---------------------------------------------------------------- buttons */

def appButtonHandler(String btn) {
    if (btn == "refreshNow") poll()
}

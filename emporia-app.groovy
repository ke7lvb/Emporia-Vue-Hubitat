/**
 *  Emporia Vue Integration (Hubitat App)
 *
 *  Replaces the single virtual-device driver with an app that:
 *   - handles login / token refresh automatically (no manual "Generate Token")
 *   - discovers Vue monitors, circuits and smart plugs and lets you pick which to add
 *   - polls asynchronously so the hub is never blocked waiting on Emporia
 *   - reports true power (W) and energy (kWh) separately
 *   - lets you switch Emporia smart plugs on and off
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
@Field static final String PLUG_DRIVER = "Emporia Smart Plug"
@Field static final String TOTAL_DNI = "emporia-total"
@Field static final String MAINS = "1,2,3"

definition(
    name: "Emporia Vue Integration",
    namespace: NAMESPACE,
    author: "Ryan Lundell",
    description: "Energy monitoring and smart plug control for Emporia Vue",
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
            input "energyScale", "enum", title: "Energy (kWh) accumulates over", defaultValue: "1D", required: true, options: [
                "1D": "Today", "1W": "This week", "1Mon": "This month", "1Y": "This year"
            ]
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
        section("Circuits and smart plugs") {
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
        def driver = info.type == "outlet" ? PLUG_DRIVER : CHANNEL_DRIVER
        try {
            addChildDevice(NAMESPACE, driver, key, [name: driver, label: info.label, isComponent: false])
            if (logEnable) log.info "Created ${info.label}"
        } catch (e) {
            log.error "Could not create ${info.label}: is the '${driver}' driver installed? ${e.message}"
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

/** Fetches the device tree and returns key -> [label, type, gid, channelNum, outlet]. */
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

    if (dev.outlet != null) {
        catalog["${gid}-${MAINS}"] = [label: "${name} (plug)", type: "outlet", gid: gid, channelNum: MAINS, outlet: dev.outlet]
    } else {
        def channels = dev.channels ?: []
        channels.each { ch ->
            def num = ch.channelNum as String
            def chName = ch.name ?: (num == MAINS ? "Mains" : "Channel ${num}")
            catalog["${gid}-${num}"] = [label: "${name} - ${chName}", type: "channel", gid: gid, channelNum: num]
        }
        if (channels.size() > 1 && channels.any { it.channelNum == MAINS }) {
            catalog["${gid}-Balance"] = [label: "${name} - Balance (unmonitored)", type: "channel", gid: gid, channelNum: "Balance"]
        }
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
    def gids = (state.catalog ?: [:]).values()*.gid.unique()
    if (!gids) return

    def instant = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
    [ "1MIN", (settings.energyScale ?: "1D") ].each { scale ->
        def query = [apiMethod: "getDeviceListUsages", deviceGids: gids.join("+"), instant: instant,
                     scale: scale, energyUnit: "KilowattHours"]
        asynchttpGet("handleUsage", apiParams("/AppAPI", query), [scale: scale])
    }
    if (getChildDevices().any { it.typeName == PLUG_DRIVER }) {
        asynchttpGet("handleDevices", apiParams("/customers/devices"))
    }
}

def handleUsage(resp, data) {
    if (!responseOk(resp, "usage")) return
    def usages = resp.json?.deviceListUsages?.devices ?: []
    boolean isPower = data.scale == "1MIN"
    BigDecimal total = 0
    String stamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))

    def catalog = state.catalog ?: [:]
    def seen = [] as Set

    // Nested plugs can show up both on their own and under their parent Vue; handle each once.
    walkUsage(usages, 0) { cu, depth ->
        String key = "${cu.deviceGid}-${cu.channelNum}"
        if (cu.usage == null || !seen.add(key)) return   // offline / minute not reported yet / duplicate
        BigDecimal kWh = cu.usage as BigDecimal
        if (depth == 0 && cu.channelNum == MAINS && catalog[key]?.type == "channel") total += kWh

        def cd = getChildDevice(key)
        if (cd) publish(cd, isPower, kWh, stamp)
    }

    def totalDev = getChildDevice(TOTAL_DNI)
    if (totalDev) publish(totalDev, isPower, total, stamp)
}

private void walkUsage(List devices, int depth, Closure visit) {
    devices.each { dev ->
        dev.channelUsages?.each { cu ->
            visit(cu, depth)
            if (cu.nestedDevices) walkUsage(cu.nestedDevices, depth + 1, visit)
        }
    }
}

private void publish(cd, boolean isPower, BigDecimal kWh, String stamp) {
    if (isPower) {
        // kWh used over one minute -> average watts during that minute
        cd.sendEvent(name: "power", value: (kWh * 60000).setScale(0, java.math.RoundingMode.HALF_UP), unit: "W")
        cd.sendEvent(name: "lastUpdate", value: stamp)
    } else {
        cd.sendEvent(name: "energy", value: kWh.setScale(3, java.math.RoundingMode.HALF_UP), unit: "kWh")
    }
}

def handleDevices(resp, data) {
    if (!responseOk(resp, "device list")) return
    def outlets = [:]
    def collect
    collect = { List devs -> devs?.each { d -> if (d.outlet != null) outlets[d.deviceGid] = d.outlet; collect(d.devices) } }
    collect(resp.json?.devices)
    def catalog = state.catalog ?: [:]
    outlets.each { gid, outlet ->
        String key = "${gid}-${MAINS}"
        getChildDevice(key)?.sendEvent(name: "switch", value: outlet.outletOn ? "on" : "off")
        if (catalog[key]) catalog[key].outlet = outlet
    }
    state.catalog = catalog   // nested edits to state maps are not persisted unless reassigned
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

/* ---------------------------------------------------------------- child callbacks */

def componentRefresh(cd) { poll() }

def setOutlet(cd, boolean on) {
    def info = state.catalog?."${cd.deviceNetworkId}"
    if (!info || !ensureToken()) {
        log.error "Cannot switch ${cd.displayName}: unknown plug or not signed in"
        return
    }
    def body = (info.outlet ?: [deviceGid: info.gid]) + [outletOn: on]
    def params = apiParams("/devices/outlet") + [requestContentType: "application/json", body: JsonOutput.toJson(body)]
    asynchttpPut("handleOutlet", params, [dni: cd.deviceNetworkId, on: on])
}

def handleOutlet(resp, data) {
    if (!responseOk(resp, "outlet update")) return
    def cd = getChildDevice(data.dni)
    def on = resp.json?.outletOn != null ? resp.json.outletOn : data.on
    cd?.sendEvent(name: "switch", value: on ? "on" : "off")
    if (logEnable) log.info "${cd?.displayName} turned ${on ? 'on' : 'off'}"
}

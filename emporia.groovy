import groovy.json.*;

metadata {
    definition(
        name: "Emporia Vue Driver 2.0",
        namespace: "ke7lvb",
        author: "Ryan Lundell",
        importUrl: "",
    ){
        capability "Refresh"
        capability "PowerSource"
        capability "PowerMeter"
		capability "EnergyMeter"

        command "authToken", [[name: "Update Authtoken*", type: "STRING"]]
		command "getDeviceGid"
        attribute "lastUpdate","string"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable Info logging", defaultValue: true, description: ""
        input name: "debugLog", type: "bool", title: "Enable Debug logging", defaultValue: true, description: ""
		input name: "jsonState", type: "bool", title: "Show JSON state", defaultValue: true, description: ""
        input("scale", "enum", title: "Scale", options: ["1S", "1MIN", "1H", "1D", "1W", "1Mon", "1Y"], required: true, defaultValue: "1H")
        input("energyUnit", "enum", title: "Energy Unit", options: ["KilowattHours"/*, "Dollars", "AmpHours", "Trees", "GallonsOfGas", "MilesDriven", "Carbon"*/], required: true, defaultValue: "KilowattHours")
        input("refresh_interval", "enum", title: "How often to refresh the Emporia data", options: [
            0: "Do NOT update",
            1: "1 Minute",
            5: "5 Minutes",
            10: "10 Minutes",
            15: "15 Minutes",
            20: "20 Minutes",
            30: "30 Minuts",
            45: "45 Minutes",
            60: "1 Hour"
        ], required: true, defaultValue: "60")
    }
}

def version(){ return "2.2.4" }

def installed(){
    if(logEnable) log.info "Driver installed"

    state.version = version()
	state.deviceGID = []
	state.deviceNames = []
}

def uninstalled() {
    unschedule(refresh)
    if(logEnable) log.info "Driver uninstalled"
}

def updated(){
    if (logEnable) log.info "Settings updated"
    if (settings.refresh_interval != "0") {
        //refresh()
        if (settings.refresh_interval == "60") {
            schedule("7 0 * ? * * *", refresh, [overwrite: true])
        } else {
            schedule("7 */${settings.refresh_interval} * ? * *", refresh, [overwrite: true])
        }
    }else{
        unschedule(refresh)
    }
    state.version = version()
	if(jsonState == false){
		state.remove("JSON")
	}
}

def getDeviceGid(){
	host = "https://api.emporiaenergy.com/"
    command = "/customers/devices"
	customer = httpGet([uri: "${host}${command}", headers:['authtoken':state.token]]){resp -> def respData = resp.data}
	if(debugLog) log.debug JsonOutput.toJson(customer.devices)
	deviceGID = []
	deviceNames = []
	customer.devices.each{ value ->
		if(debugLog) log.debug value.deviceGid
		
		deviceGID.add(value.deviceGid)
		
		channels = value.devices[0].channels
		channels.each{ next_value ->
			deviceNames.add(next_value.name)
		}
	}
	state.deviceGID = deviceGID
	deviceNames = deviceNames - null
	deviceNames = deviceNames - ''
	state.deviceNames = deviceNames
}

def refresh() {
	if(state.deviceGID){
		//set timestamp to now
		Gid_string = state.deviceGID.join("+")
		outputTZ = TimeZone.getTimeZone('UTC')
		instant = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'",outputTZ)
		
		//Make API call
		host = "https://api.emporiaenergy.com/"
		command = "AppAPI?apiMethod=getDeviceListUsages&deviceGids=${Gid_string}&instant=${instant}&scale=${scale}&energyUnit=${energyUnit}"
		if(debugLog) log.debug "${host}${command}"
		JSON = httpGet([uri: "${host}${command}", headers:['authtoken':state.token]]){resp -> def respData = resp.data}
		if(jsonState){
			state.JSON = JsonOutput.toJson(JSON)
		}
		devices = JSON.deviceListUsages.devices
        combinedTotals = 0;
		//loop through results to get device GID
		devices.each{value ->
			channelUsages = value.channelUsages
			if(value.deviceGid == state.deviceGID[0]){
				first_device = true
			}else{
				first_device = false	
			}
			//loop through channels to get names and values
			channelUsages.each{ next_value ->
				if(debugLog) log.debug next_value
				name = next_value.name
                
                usage = next_value.usage
                if(usage == null){
                    if(debugLog) log.debug "null value encountered on ${name}"
                    return;
                }
				Wh = convertToWh(usage) ?: 0
                
                if(name == "Main"){
                    combinedTotals = combinedTotals + Wh
                }
                
				if(name == "Main" || name == "TotalUsage" || name == "Balance"){
					Gid = next_value.deviceGid
					name = name+"_"+Gid
				}

				
                //create/update child device power value
                def cd = fetchChild(name)
                cd.parse([[name:"power", value:Wh]])
											
			}
		}
        
        sendEvent(name: "power", value: combinedTotals)
		sendEvent(name: "energy", value: combinedTotals/1000)
		
		//send last updated timestamp
        now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
		state.lastUpdate = timeToday(now)
		sendEvent(name: "lastUpdate", value: state.lastUpdate)
    }else{
        log.info "device Gid not found. Please run the command to Get Device Gid"
    }
}

def authToken(token){
    state.token = token

    now = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
    state.lastTokenUpdate = timeToday(now)
}

def convertToWh(usage){
	if(usage != null){
		switch(scale){
			case "1S":
				Math.round(usage * 60 * 60 * 1000)
				break;
			
			case "1MIN":
				Math.round(usage * 60 * 1000)
				break;

			default:
				Math.round(usage * 1000)
				break;
		}
	}
}

def fetchChild(name){
    String thisId = device.id
    def cd = getChildDevice(name)
    if (!cd) {
        cd = addChildDevice("hubitat", "Generic Component Power Meter", name, [name: name, isComponent: false])
    }
    return cd 
}

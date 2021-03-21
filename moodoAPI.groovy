/*
** Moodo API driver
**
** Copyright 2021 Guy Sprackland
** 
** Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
** in compliance with the License. You may obtain a copy of the License at:
**
**      http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
** on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
** for the specific language governing permissions and limitations under the License.
**
**
** Version	Date		By	Why
** V0.1		21-Mar-2021	GNS	Default logs off, fix typo, error check create child
**					Make email and password required in connect command
** V0.0		17-Mar-2021	GNS	Initial functionality complete (ish)
**
*/ 

//
// Instructions:
//
// Install the drivers for Moodo API, Moodo Box and Moodo Slot into Drivers Code in the hub user interface.
//
// In Devices, add a new virtual device of type Moodo API. Do not create devices for Moodo Box or Moodo Slot.
//
// In the device page for your Moodo API device, fill your Moodo cloud email address and password into the
// Connect box in Commands, and click Connect.
//
// The driver will connect to the Moodo cloud, get a list of your Moodo boxes and create a child device
// for each box named whatever you called it when you set it up. The device is a dimmer and switch and
// also has some custom commands for controlling the other features of the box.
//
// The driver does not store your credentials nor need them again unless you disconnect from Moodo.
//
// Each Moodo box device has 4 child devices of its own for controlling the fans in each slot individually.
//
// At the moment real time updates from Moodo aren't implemented, so there is a user settable polling
// interval in the API driver.
//
//

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

import java.net.URLEncoder

metadata {
	definition(name: "Moodo API", namespace: "guysprackland", author: "Guy Sprackland", importUrl: "https://raw.githubusercontent.com/GuySprackland/hubitat-moodo/main/moodoAPI.groovy") {

		capability "Initialize"
		capability "Refresh"

		command "connect", [[name:"Email*", type: "STRING", description: "Moodo registered email address", constraints: ["STRING"]], [name:"Password*", type: "STRING", description: "Moodo password", constraints: ["STRING"]]]
		command "disconnect"

		attribute "loginStatus", "NUMBER"

	}
}

preferences {
	section {
		input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        	input name: "pollInterval", type: "number", title: "Poll interval in seconds", defaultValue: 600
	}
}

//
// Built in callbacks
//

void installed() {

	log.warn "Installed"
    
//	Initialise state variables
//	Initialise attributes

	configure()
}

void uninstalled() {

	if (state.authToken) {
		logout()
	}
	deleteChildren()
}

void updated() {
	log.info "updated..."

	log.warn "debug logging is: ${logEnable == true}"
	if (logEnable) runIn(1800, logsOff)

	refresh()
}

// For those idle moments when I get round to adding the websockets support

def parse(String description) {
//	if (logEnable) log.debug(description)
	log.debug("Parse: " + description)
}

def webSocketStatus(String message) {
	log.debug ("WebSocketStatus: " + message)

}

//
// Capabilities
//

void initialize() {

	def token = GetDataValue("token")

	if (token) {
		state.authToken = token
		sendEvent(name: "loginStatus", value: 1)
		runIn (1, refresh)
	} else {
		state.clear()
		sendEvent(name: "loginStatus", value: 0)
	}
}

void refresh() {

	unschedule(refresh)

	if (state.authToken) {
		getBoxes()
		getIntervals()
		createChildren()
	}

	if (pollInterval > 0) {
		runIn (pollInterval, refresh)				// Bodge only
	}
}

//
// Commands
//

void connect(user, password) {

	def result
	String token

	def request = [:]

	request.email = user
	request.password = password

	result = moodo("post" , "login", null , JsonOutput.toJson(request), false)

	if (result) {
		token = result?.token
		state.authToken = token
		updateDataValue("token", token)

		sendEvent(name: "loginStatus", value: 1, isStateChange: true)

		runIn (1, refresh)			// Give it a chance to settle. Otherwise first read of child box details fails with not available.

//		def socketheaders = ["Content-Type" : "application/json"]
//
//		log.debug "Before websocket connect"
//		try {
//			interfaces.webSocket.connect("wss://ws.moodo.co:9090/", pingInterval: 60, headers: socketheaders)
//			interfaces.webSocket.connect("wss://ws.moodo.co:9090/")
//		}
//
//		catch (Exception e) {
//        		log.debug "Websocket connect failed: " + e.message
//		}
//
//		log.debug "After websocket connect"
	}
}


void disconnect() {

	unschedule()

//	interfaces.webSocket.close()
	moodo("post", "logout")
	sendEvent(name: "loginStatus", value: 0, isStateChange: false)
	state.authToken = ""
	removeDataValue("token")
}

//
// Get information about all boxes for initial child device creation
//

void getBoxes() {

	def result
	def boxes

	result = moodo("get", "boxes")

	if (result) {
		boxes = result?.boxes

        	state.boxes =[]
            	boxes?.each { theBox ->
        		state.boxes << [ "name":"${theBox.name}", "device_key":"${theBox.device_key}"]
		}
	}
}

//
// Cache interval types and provide some simple translations from type to name and name to type
//

void getIntervals() {
	
	def result = moodo("get", "interval")

	if (result) {
		state.intervals = result?.interval_types
	}
}

def matchInterval(intervalType) {

	def matched = null

	if (state?.intervals) {
		matched = state.intervals.find { interval -> interval.type == intervalType }
	}

	return matched
}

def findInterval(intervalName) {

	def matched = null

	if (state?.intervals) {
		matched = state.intervals.find { interval -> interval.description.toLowerCase().contains(intervalName.toLowerCase()) }
	}

	return matched

}

//
// Callbacks for child devices to talk to the Moodo cloud
//

def getStatus(childId) {

	def result = moodo("get", "boxes", getKey(childId))

	if (result) {
		return result?.box
	} else {
		return null
	}
}

def getFavourites(childId, title = null) {

	def path = getKey(childId)

	if (title) {
//		path += "/" + URLEncoder.encode(title.toString())		// Maps space to "+" not "%20"
		path += "/" + title.replaceAll(" ", "%20")
	}

	def result = moodo("get", "favorites/false", path)

	if (result) {
		return result?.favorites
	} else {
		return null
	}
}

def setFavourite(childId, favouriteId, fanVolume=0, duration=0) {

	def request = [:]

	request.favorite_id = favouriteId
	request.device_key = new Integer(getKey(childId))

	if ((fanVolume > 0) && (fanVolume <= 100)) {
		request.fan_volume = fanVolume
	}

	if (duration > 0) {
		request.duration = duration
	}

	return moodo("patch", "favorites", null, JsonOutput.toJson(request))
}

def powerOn (childId) {

        return moodo("post", "boxes", getKey(childId))
}

def powerOff (childId) {

        return moodo("delete", "boxes", getKey(childId))
}

def kick (childId, fanVolume = 0, duration = 0, favouriteId = null) {

	def request = [:]

	if ((fanVolume > 0) && (fanVolume <= 100)){
		request.fan_volume = fanVolume
	}

	if (duration > 0) {
		request.duration = duration
	}

	if (favouriteId) {
		request.favorite_id = favouriteId
	}

	return moodo("post", "boxes", getKey(childId), JsonOutput.toJson(request))
}

def setLevel (childId, level) {

	def request = [:]

	request.fan_volume = level

        return moodo("post", "intensity", getKey(childId), JsonOutput.toJson(request))
}

def setMode (childId, mode) {

	def request = [:]

	request.box_mode = mode

        return moodo("post", "mode", getKey(childId), JsonOutput.toJson(request) )
}

def setFans (childId, level, power, s0, a0, s1, a1, s2, a2, s3, a3) {

	def request = [:]
	def slot0 = [:]
	def slot1 = [:]
	def slot2 = [:]
	def slot3 = [:]

	slot0.fan_speed = s0
	slot0.fan_active = a0

	slot1.fan_speed = s1
	slot1.fan_active = a1

	slot2.fan_speed = s2
	slot2.fan_active = a2

	slot3.fan_speed = s3
	slot3.fan_active = a3

	request.device_key = new Integer(getKey(childId))
	request.fan_volume = level
	request.box_status = new Integer(power)

	request.settings_slot0 = slot0
	request.settings_slot1 = slot1
	request.settings_slot2 = slot2
	request.settings_slot3 = slot3

	return moodo("post", "boxes", "", JsonOutput.toJson(request) )
}

def setIntervalType(childId, intervalType) {

	def request = [:]

	request.interval_type = new Integer(intervalType)

       	return moodo("post", "interval", getKey(childId), JsonOutput.toJson(request) )
}


def setShuffle (childId, onOff) {

	return OnOff("shuffle", childId, onOff)
}

def setInterval (childId, onOff) {

	return OnOff("interval", childId, onOff)
}

def OnOff(what, childId, onOff) {

	def box_key = getKey(childId)

	if (onOff == "on") {
        	return moodo("post", what, box_key)
	} else {
        	return moodo("delete", what, box_key)
	}
}

//
// Send a request to Moodo
//

def moodo(method, path, id = null, sendData = null, useToken = true) {

	def httpParams = [:]
	def receiveData = null

	def uri
	def headers = [:]

	uri = "https://rest.moodo.co/api/" + path
	if (id) {
		uri += "/" + id
	}

	if (useToken) {
		headers << ["token" : state.authToken]
	}

	if (method.toLowerCase() == "patch") {
		httpParams.contentType	= "application/json"
	} else {
		headers << ["accept" : "application/json"]

		if (sendData) {
			headers << ["Content-Type" : "application/json"]
		}
	}

	httpParams.uri		= uri
	httpParams.headers	= headers

	if (sendData) {
		httpParams.body = sendData
		if (logEnable) log.debug "Moodo request: " + method + " " + uri + " " + sendData
	} else {
		if (logEnable) log.debug "Moodo request: " + method + " " + uri
	}


	try {
		switch (method.toLowerCase()) {

		case "get":
			httpGet(httpParams, {httpResponse -> if (httpResponse) receiveData = httpResponse.data})
			break

		case "post":
			httpPost(httpParams, {httpResponse -> if (httpResponse) receiveData = httpResponse.data})
			break

		case "delete":
			httpDelete(httpParams, {httpResponse -> if (httpResponse) receiveData = httpResponse.data})
			break

		case "put":
			httpPut(httpParams, {httpResponse -> if (httpResponse) receiveData = httpResponse.data})
			break

		case "patch":
			httpPatch(httpParams, {httpResponse -> if (httpResponse) receiveData = httpResponse.data})
			break

		default:
			log.debug "Unsupported Moodo http request method - " + method
		}

	}

	catch (Exception e) {

//		It's a 503 - Service Unavailable
//		You get this for every error, but the requested box is probably offline.

		log.debug "Moodo " + method + " " + path + " " + id + " request failed: " + e.message

//		The response includes an object with error text, but not sure how I can get at it
//		since the docs say that only success returns the data, and 503 isn't a success

//		def errorMessage = httpResponse?.data?.error
//		if (errorMessage) {
//			log.debug errorMessage
//		}

	}

	if (logEnable) log.debug "Moodo response: " + receiveData

	return receiveData
}

//
// Support functions
//

def getKey(childId) {
	return childId?.substring(6)
}

String getID(boxKey) {
	return "Moodo_" + boxKey
}

void createChildren() {

	String					childId
	com.hubitat.app.ChildDeviceWrapper	childDevice

	state.boxes?.each {

		if (it?.device_key) {
			childId = getID(it.device_key)

			if (!(childDevice = getChildDevice(childId))) {

				if (logEnable) log.debug "Creating " + it.name
				try {
					childDevice = addChildDevice("Moodo box", childId, [label: it.name, isComponent: false])
				}
				catch (Exception e) {
					log.debug "Problem creating child device: " + e.message
					childDevice = null
				}
			}
			childDevice?.refresh()
		}
	}
}

void deleteChildren() {

//	List<com.hubitat.app.ChildDeviceWrapper> childDevices = getChildDevices()

//	String networkId

//	childDevices?.each {
//		networkId = it?.getDeviceNetworkId()

//		if (networkId) {
//			deleteChildDevice(networkId)
//		}
//	}


	def childId

	state.boxes?.each {

		if (it?.device_key) {
			childId = getID(it.device_key)
			deleteChildDevice(childId)
		}
	}
}

void logsOff() {
	log.warn "debug logging disabled..."
	device.updateSetting("logEnable", [value: "false", type: "bool"])
}


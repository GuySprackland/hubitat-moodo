/*
** Moodo Box child driver
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
** V0.0		17-Mar-2021	GNS	Initial functionality complete (ish)
**
*/

// See the Moodo API driver for brief, inadequate and probably misleading instructions

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata
{
    definition(name: "Moodo box", namespace: "guysprackland", author: "Guy Sprackland", importUrl: "")
    {
	capability "Actuator"
        capability "Switch"
	capability "SwitchLevel"
	capability "Refresh"
	capability "Battery"

	command(
             "setShuffle", 
             [
                [
                     "name":"Shuffle*",
                     "description":"Enable or disable shuffle mode",
                     "type":"ENUM",
                     "constraints":["on","off"]
                ]
             ]
        )

	command(
             "setInterval", 
             [
                [
                     "name":"Interval*",
                     "description":"Enable or disable interval mode",
                     "type":"ENUM",
                     "constraints":["on","off"]
                ]
             ]
        )

	command(
             "setMood", 
             [
                [
                     "name":"mood*",
                     "description":"Select a mood by name",
                     "type":"STRING"
                ],
                [
                     "name":"fanVolume*",
                     "description":"Fan volume %, or 0 for no change",
                     "type":"NUMBER"
                ],
                [
                     "name":"duration*",
                     "description":"Duration in minutes, or 0 to not turn off",
                     "type":"NUMBER"
                ]
             ]
        )

	command(
             "kickStart", 
             [
                [
                     "name":"fanVolume*",
                     "description":"Fan volume %, or 0 for no change",
                     "type":"NUMBER"
                ],
                [
                     "name":"duration*",
                     "description":"Duration in minutes, or 0 to not turn off",
                     "type":"NUMBER"
                ],
                [
                     "name":"mood",
                     "description":"Optional mood name or part name",
                     "type":"STRING"
                ]

             ]
        )

	command(
             "setMode", 
             [
                [
                     "name":"Mode*",
                     "description":"Change mode",
                     "type":"ENUM",
                     "constraints":["diffuser","purifier"]
                ]
             ]
        )

	command(
             "setIntervalType", 
             [
                [
                     "name":"Type*",
                     "description":"Set interval type",
                     "type":"ENUM",
                     "constraints":["powerful Moodo","efficient Moodo", "saver Moodo"]
                ]
             ]
        )

	attribute "interval", "ENUM", ["on","off"]
	attribute "intervalType", "STRING"
	attribute "shuffle", "ENUM", ["on","off"]
	attribute "status", "ENUM", ["online","offline"]
	attribute "state", "ENUM", ["on", "off", "sleeping"]
	attribute "family", "STRING"
	attribute "mood", "STRING"
	attribute "mode", "STRING"
	attribute "charging", "BOOL"
  }
}


void on() {

	def result = getParent()?.powerOn(device.getDeviceNetworkId())

	if (result) {

		setupBox(result?.box)
	}
}

void off() {

	def result = getParent()?.powerOff(device.getDeviceNetworkId())
	if (result) {

		setupBox(result?.box)
	}
}

void setLevel(level, duration=0) {

	// Turn on and set level from a scene doesn't appear to issue the on or off,
	// so turn the box on if the fan is on

	if (level > 0) {
		on()
	} else {
		off()
	}

	// The moodo set intensity command does not return a full box object

	def result = getParent()?.setLevel(device.getDeviceNetworkId(),level)

	newLevel = result?.box?.fan_volume ?: level

	sendEvent(name:"level", value: newLevel)
}

void setMood(mood, fanVolume=0, duration=0) {

	def childId = device.getDeviceNetworkId()

	def favourites = getParent()?.getFavourites(childId, mood)

	if (favourites) {

		def favourite = favourites[0]
		def favouriteId = favourite?.id

		if (favouriteId) {

			result = getParent()?.setFavourite(childId, favouriteId, fanVolume, duration)

			if (result) {
				setupBox(result?.box)					// Favourite may not have applied yet!
											// Can take more than 10 seconds
				runIn(15, refresh)					// Take a look again after a bit (bodge)
			}
		}
	}
}

void kickStart(fanVolume=0, duration=0, mood=null) {

	def childId = device.getDeviceNetworkId()
	def favouriteId = null

	if (mood) {
		def favourites = getParent()?.getFavourites(childId, mood)

		if (favourites) {

			def favourite = favourites[0]
			favouriteId = favourite?.id
		}
	}

	result = getParent()?.kick(childId, fanVolume, duration, favouriteId)

	if (result) {
		setupBox(result?.box)
		runIn(15, refresh)		// Take a look again after a bit (bodge)
	}

}


void setFanLevel(slot, level) {

	def pswitch = device.currentValue("switch")
	def plevel = device.currentValue("level")

	def power

	Boolean[] active = [state.fans[0].fan_active, state.fans[1].fan_active, state.fans[2].fan_active, state.fans[3].fan_active]
	Integer[] speed = [state.fans[0].fan_speed, state.fans[1].fan_speed, state.fans[2].fan_speed, state.fans[3].fan_speed]

	speed[slot] = level

	if (pswitch == "on") {
		power = 1
	} else {
		power = 0
	}

	def result = getParent()?.setFans(	device.getDeviceNetworkId(),
						plevel, power,
						speed[0], active[0],
						speed[1], active[1],
						speed[2], active[2],
						speed[3], active[3])

	if (result) {

		setupBox(result?.box)
	}

}

void setFanOn(slot) {

	def pswitch = device.currentValue("switch")
	def plevel = device.currentValue("level")

	def power

	Boolean[] active = [state.fans[0].fan_active, state.fans[1].fan_active, state.fans[2].fan_active, state.fans[3].fan_active]
	Integer[] speed = [state.fans[0].fan_speed, state.fans[1].fan_speed, state.fans[2].fan_speed, state.fans[3].fan_speed]

	active[slot] = true

	if (pswitch == "on") {
		power = 1
	} else {
		power = 0
	}

	def result = getParent()?.setFans(	device.getDeviceNetworkId(),
						plevel, power,
						speed[0], active[0],
						speed[1], active[1],
						speed[2], active[2],
						speed[3], active[3])

	if (result) {

		setupBox(result?.box)
	}

}

void setFanOff(slot) {
	def pswitch = device.currentValue("switch")
	def plevel = device.currentValue("level")

	def power

	Boolean[] active = [state.fans[0].fan_active, state.fans[1].fan_active, state.fans[2].fan_active, state.fans[3].fan_active]
	Integer[] speed = [state.fans[0].fan_speed, state.fans[1].fan_speed, state.fans[2].fan_speed, state.fans[3].fan_speed]

	active[slot] = false

	if (pswitch == "on") {
		power = 1
	} else {
		power = 0
	}

	def result = getParent()?.setFans(	device.getDeviceNetworkId(),
						plevel, power,
						speed[0], active[0],
						speed[1], active[1],
						speed[2], active[2],
						speed[3], active[3])

	if (result) {

		setupBox(result?.box)
	}

}


void setShuffle(shuffleOnOff) {

	def result = getParent()?.setShuffle(device.getDeviceNetworkId(),shuffleOnOff)
	if (result) {

		setupBox(result?.box)
	}
}

void setInterval(intervalOnOff) {

	def result = getParent()?.setInterval(device.getDeviceNetworkId(),intervalOnOff)
	if (result) {

		setupBox(result?.box)
	}
}

void setMode(newMode) {

	def result = null

	switch (newMode) {
	case "diffuser":

		if (getDataValue("diffuser") == "true") {
			result = getParent()?.setMode(device.getDeviceNetworkId(),"diffuser")
		}
		break ;

	case "purifier":

		if (getDataValue("purifier") == "true") {
			result = getParent()?.setMode(device.getDeviceNetworkId(),"purifier")
		}
		break ;
	}

	if (result) {

		setupBox(result?.box)
	}
}

void setIntervalType(newType) {

	def result = null

	if (newType) {
		def matched = getParent()?.findInterval(newType)

		if (matched) {

			result = getParent()?.setIntervalType(device.getDeviceNetworkId(), matched?.type)

			if (result) {
				setupBox(result?.box)
			}
		}
	}
}


void setupBox(box, refreshFavourites = false) {

	String			thisDevice = device.getDeviceNetworkId()
	
	String			name
	String			thisName

	if (box) {

		def		boxVersion = box?.box_version
		def		level = box?.fan_volume
		def		interval = box?.interval
		def		intervalType = box?.interval_type
		def		shuffle = box?.shuffle
		def		online = box?.is_online
		def		power = box?.box_status
		def		mode = box?.box_mode
		def		hasBattery = box?.has_battery
		def		isBatteryCharging = box?.is_battery_charging
		def		batteryLevelPercent = box?.battery_level_percent
		def		canIntervalTurnOn = box?.can_interval_turn_on

		name = box?.name
		thisName = name + " slot "

		sendEvent(name:"level", value: level)

		if (interval != null) {

			if (interval) {
				sendEvent(name:"interval", value: "on")
				def intervalDetails = getParent()?.matchInterval(intervalType)
				if (intervalDetails) {
					sendEvent(name:"intervalType", value: intervalDetails?.description)
				}
			} else {
				if (device.currentValue("interval") != "off") {
					sendEvent(name:"interval", value: "off")
					sendEvent(name:"intervalType", value: "off")
				}
			}
		}

		if (shuffle != null) {
			if (shuffle) {
				sendEvent(name:"shuffle", value: "on")
			} else {
				sendEvent(name:"shuffle", value: "off")
			}
		}

		if (online != null) {
			if (online) {
				sendEvent(name:"status", value: "online")
			} else {
				sendEvent(name:"status", value: "offline")
			}
		}

		switch (power) {
		case 1:
			sendEvent(name:"switch", value: "on")
			sendEvent(name:"state", value: "on")
			break ;
		case 0:
			sendEvent(name:"switch", value: "off")

			if ((interval == true) && (canIntervalTurnOn == true)) {
				sendEvent(name:"state", value: "sleeping")
			} else {
				sendEvent(name:"state", value: "off")
			}
			break ;
		}

		sendEvent(name: "mode", value: mode)

		updateDataValue("id", box?.id)
		updateDataValue("device_key", box?.device_key.toString())
		updateDataValue("box_version", boxVersion.toString())
		updateDataValue("has_battery", hasBattery.toString())
		updateDataValue("diffuser", box?.is_diffuser_mode_available.toString())
		updateDataValue("purifier", box?.is_purifier_mode_available.toString())

//
//		Only report charging information if the box has a battery.
//		Only report battery percentage for V3 boxes that provide this
//		V2 boxes don't provide charge state of battery, just whether it's charging

		if (hasBattery) {
			if (isBatteryCharging != null) {
				sendEvent(name: "charging",value: isBatteryCharging)
			}
			// Actual reported battery percentage always 0 on version 2 box
			if (boxversion > 2) {
				sendEvent(name: "battery",value: batteryLevelPercent.toString())
			}
		}

		def settings = box?.settings

		if (settings) {

			// Update each slot with its fan and capsule details

			String		slotDevice
			String		slotLabel
			Object		childDevice				// com.hubitat.app.ChildDeviceWrapper
			Integer[]	capsules = [0, 0, 0, 0]

			def		child = [:]
			def		children = []
			def		fan = [:]
			def		fans = []

			def		fragranceLeftPercent
			def		slotManualUsagePercent

			settings?.each { thisSlot ->
				child = [:]
				child.slot = thisSlot?.slot_id
				child.capsuleNumber = thisSlot?.capsule_type_code
				child.capsuleName = thisSlot?.capsule_info.title
				child.capsuleColour = thisSlot?.capsule_info.color
				child.isDigital = thisSlot?.capsule_info?.is_digital
				child.fan_speed = thisSlot?.fan_speed
				child.fan_active = thisSlot?.fan_active

				// For version 3 boxes, pass frangrance usage details. Working blind here.

				if (boxversion > 2) {
					fragranceLeftPercent = thisSlot?.fragrance_left_percent
					if (fragranceLeftPercent) {
						child.fragranceLeftPercent = fragranceLeftPercent		// Only include the fields if non null
					}

					slotManualUsagePercent = thisSlot?.slot_manual_usage_percent
					if (slotManualUsagePercent) {
						child.slotManualUsagePercent = slotManualUsagePercent
					}
				}

				fan = [:]
				fan.fan_speed = thisSlot?.fan_speed
				fan.fan_active = thisSlot?.fan_active

				if ((child.slot >= 0) && (child.slot < 4)) {
					capsules[child.slot] = child.capsuleNumber
					children[child.slot] = child
					fans[child.slot] = fan
				}

				slotDevice = thisDevice + "-" + child.slot
				slotLabel = thisName + child.slot

				if (!(childDevice = getChildDevice(slotDevice))) childDevice = addChildDevice("Moodo slot", slotDevice, [label: slotLabel, isComponent: true])

				childDevice?.setupSlot(child)

			}
			state.fans = fans

			// If the capsules have been changed or swapped around then get the new list of favourites for the box
			// Will not pick up any new favourites added, or favourites deleted unless a refresh() is done.

			if (state.capsules) {
				if ((state.capsules != capsules) || (refreshFavourites == true)) {
					state.capsules = capsules
					rebuildFavourites(box)
				}
			} else {
				state.capsules = capsules
				rebuildFavourites(box)
			}
		}
	}


}

void Configure() {
	state.clear()
	refresh()
}

void refresh() {

	unschedule(refresh)

	def box = getParent()?.getStatus(device.getDeviceNetworkId())

	setupBox(box, true)
}

void rebuildFavourites(box) {

	def favouriteID =""

	if (box) {

		favouriteID = box?.favorite_id_applied
	}

	// Dredge through the favourites for this box until we find one
	// with all fans set to 100%. The name of this one is also the name for
	// the scent family. Probably.

	def favourites = getParent()?.getFavourites(device.getDeviceNetworkId())
	def title
	def slot
	def capsule
	def fan
	def fan_total
	Boolean foundFamily = false
	def favNames = []

	if (favourites) {

           	favourites?.each { theFavourite ->
        		title = theFavourite?.title

			if (favouriteID == theFavourite?.id) {
				sendEvent(name: "mood", value: title)
			}

			favNames << title
	
			slot = theFavourite?.settings
			fan_total = 0
			slot?.each {thisSlot ->
				capsule = thisSlot?.capsule_info?.title
				fan = thisSlot?.fan_speed
				fan_total += fan
			}
			if (fan_total == 400) {
				sendEvent(name: "family", value: title)
				foundFamily = true
			}
		}

		if (!foundFamily) {
			sendEvent(name: "family", value: "Unknown")
		}

		state.moods = favNames
	}
}


void installed() {
	Integer[] capsules=[0, 0, 0, 0]
   
	state.clear()
	state.capsules = capsules
}

void uninstalled() {
}

void updated() {
}

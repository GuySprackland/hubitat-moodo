/*
** Moodo box slot child child driver
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

metadata
{
    definition(name: "Moodo slot", namespace: "guysprackland", author: "Guy Sprackland", importUrl: "")
    {
        capability "Switch"
	capability "SwitchLevel"

	attribute "slotNumber", "NUMBER"
	attribute "capsuleName", "STRING"
	attribute "capsuleNumber", "NUMBER"
	attribute "capsuleColour", "STRING"
	attribute "capsuleDigital", "BOOLEAN"
	attribute "fragranceLeft", "NUMBER"

  }
}

void on() {

	getParent()?.setFanOn(device.currentValue("slotNumber"))
}

void off() {

	getParent()?.setFanOff(device.currentValue("slotNumber"))
}

void setLevel(level) {

	getParent()?.setFanLevel(device.currentValue("slotNumber"), level)

}


def setupSlot(child) {

	sendEvent(name:"slotNumber", value: child.slot)
	sendEvent(name:"capsuleNumber", value: child.capsuleNumber.toInteger())
	sendEvent(name:"capsuleName", value: child.capsuleName)
	sendEvent(name:"capsuleColour", value: child.capsuleColour)
	sendEvent(name:"capsuleDigital", value: child.isDigital.toString())

	if (child.fan_active) {
		sendEvent(name:"switch", value: "on")
	} else {
		sendEvent(name:"switch", value: "off")
	}

	sendEvent(name:"level", value: child.fan_speed.toInteger())

	if (child.fragranceLeftPercent != null) {
		sendEvent("fragranceLeft", child.fragranceLeftPercent.toInteger(), unit: "%")
	}
}

void installed() {
}

void uninstalled() {
}

void updated() {
}

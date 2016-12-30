/**
 *  Xiaomi Mi Cube
 *
 *  Author: Oleg Smirnov
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {
	definition (name: "Xiaomi Mi Cube", namespace: "droidsector", author: "Oleg Smirnov") {
        capability "Actuator"
		capability "Button"
		capability "Configuration"
		capability "Battery"
        capability "Indicator"
		capability "Sensor"
        
        attribute "currentButton", "STRING"
        attribute "numButtons", "STRING"
	}

	simulator {
	}

//	preferences {
//	}

	tiles {
    	standardTile("button", "device.button", width: 1, height: 1) {
			state "default", label: "", icon: "st.unknown.zwave.remote-controller", backgroundColor: "#ffffff"
		}
    
		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
		
		main(["button"])
		details(["button", "battery"])
	}
}

def parse(String description) {
	log.debug "description: $description"
    def value = zigbee.parse(description)?.text
    log.debug "Parse: $value"
	Map map = [:]
	if (description?.startsWith('catchall:')) {
		map = parseCatchAllMessage(description)
	}
	else if (description?.startsWith('read attr -')) {
		map = parseReportAttributeMessage(description)
	}
 
	log.debug "Parse returned $map"
	def result = map ? createEvent(map) : null
    
    if (description?.startsWith('enroll request')) {
    	List cmds = enrollResponse()
        log.debug "enroll response: ${cmds}"
        result = cmds?.collect { new physicalgraph.device.HubAction(it) }
    }

    return result
}

// not tested
private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def cluster = zigbee.parse(description)
	log.debug "parseCatchAllMessage"
    log.debug cluster
	if (shouldProcessMessage(cluster)) {
		switch(cluster.clusterId) {
			case 0x0001:
			resultMap = getBatteryResult(cluster.data.last())
			break

			case 0x0402:
			log.debug 'TEMP'
				// temp is last 2 data values. reverse to swap endian
				String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
				def value = getTemperature(temp)
				resultMap = getTemperatureResult(value)
				break
		}
	}

	return resultMap
}

// not tested
private boolean shouldProcessMessage(cluster) {
	// 0x0B is default response indicating message got through
	// 0x07 is bind message
	boolean ignoredMessage = cluster.profileId != 0x0104 ||
	cluster.command == 0x0B ||
	cluster.command == 0x07 ||
	(cluster.data.size() > 0 && cluster.data.first() == 0x3e)
	return !ignoredMessage
}

private Map parseReportAttributeMessage(String description) {
	Map descMap = (description - "read attr - ").split(",").inject([:]) { map, param ->
		def nameAndValue = param.split(":")
		map += [(nameAndValue[0].trim()):nameAndValue[1].trim()]
	}
	 
	Map resultMap = [:]

	if (descMap.cluster == "0001" && descMap.attrId == "0020") {
		resultMap = getBatteryResult(Integer.parseInt(descMap.value, 16))
	}
    else if (descMap.cluster == "0012" && descMap.attrId == "0055") { // Shake, flip, knock, slide
    	resultMap = getMotionResult(descMap.value)
    } 
    else if (descMap.cluster == "000C" && descMap.attrId == "ff05") { // Rotation (90 and 180 degrees)
    	resultMap = getRotationResult(descMap.value)
           
    }
    
	return resultMap
}
 
private Map parseCustomMessage(String description) {
	Map resultMap = [:]
	return resultMap
}
/*
private Map parseIasMessage(String description) {
    List parsedMsg = description.split(' ')
    String msgCode = parsedMsg[2]
    
    Map resultMap = [:]
    switch(msgCode) {
        case '0x0020': // Closed/No Motion/Dry
        	resultMap = getMotionResult('inactive')
            break

        case '0x0021': // Open/Motion/Wet
        	resultMap = getMotionResult('active')
            break

        case '0x0022': // Tamper Alarm
        	log.debug 'motion with tamper alarm'
        	resultMap = getMotionResult('active')
            break

        case '0x0023': // Battery Alarm
            break

        case '0x0024': // Supervision Report
        	log.debug 'no motion with tamper alarm'
        	resultMap = getMotionResult('inactive')
            break

        case '0x0025': // Restore Report
            break

        case '0x0026': // Trouble/Failure
        	log.debug 'motion with failure alarm'
        	resultMap = getMotionResult('active')
            break

        case '0x0028': // Test Mode
            break
    }
    return resultMap
}
*/

private Map getBatteryResult(rawValue) {
	log.debug 'Battery'
	def linkText = getLinkText(device)

	log.debug rawValue

	def result = [
		name: 'battery',
		value: '--'
	]

	def volts = rawValue / 10
	def descriptionText

	if (rawValue == 0) {}
	else {
		if (volts > 3.5) {
			result.descriptionText = "${linkText} battery has too much power (${volts} volts)."
		}
		else if (volts > 0){
			def minVolts = 2.1
			def maxVolts = 3.0
			def pct = (volts - minVolts) / (maxVolts - minVolts)
			result.value = Math.min(100, (int) pct * 100)
			result.descriptionText = "${linkText} battery was ${result.value}%"
		}
	}

	return result
}

private Map getMotionResult(value) {
	String linkText = getLinkText(device)
    String motion, button
   
   	// first byte: type of motion
    if (value.startsWith("00")) {
 		
       	// rotation - last 2 bytes
        int angle = Long.parseLong(value.substring(2, 4), 16)
        
        switch (angle) {
        	
        case 0: 
        	motion = "Shake"
        	button = "1"
            break
            
        case 2:
            log.debug "Small shake, ignoring..."
        	return null
            
        case 3..127:
        	motion = "Flip 90"
        	button = "2"
            break
            
        default:
        	motion = "Flip 180"
        	button = "3"
            break
            
        }
    }         
    else if (value.startsWith("01")) {
        motion = "Slide"
        button = "4"
    }
    else if (value.startsWith("02")) {
        motion =  "Knock"
        button = "5"
    }

//	log.debug motion

    def commands = [
		name: "button",
		value: motion,
        data: [buttonNumber: button],
		descriptionText: "${linkText} detected motion: ${motion} ",
        isStateChange: true
	] 
    
    return commands
}

private Map getRotationResult(value) {
   
	String linkText = getLinkText(device)
	String button, motion
	
    // first 8 bytes are related to rotation
    String rotation = value.substring(0, 8)
   
	if (Long.parseLong(rotation, 16) < 0x80000000) {
        motion = "Rotate right"
        button = 6
    } else {
    	motion = "Rotate left"
        button = 7
    }
	
//  log.debug motion
	    
    def commands = [
		name: "button",
		value: motion,
        data: [buttonNumber: button],
		descriptionText: "${linkText} detected motion: ${motion} ",
        isStateChange: true
	]
    
    return commands
}

def configure() {
	String zigbeeEui = swapEndianHex(device.hub.zigbeeEui)
	log.debug "Configuring Reporting, IAS CIE, and Bindings."
	def configCmds = []
    return configCmds + refresh() // send refresh cmds as part of config
}

def enrollResponse() {
	log.debug "Sending enroll response"
}

def reset() {
	
}

def initialize() {
	sendEvent(name: "numberOfButtons", value: 7)
}
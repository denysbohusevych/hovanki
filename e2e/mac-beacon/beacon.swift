// The radar's Bluetooth on a Mac, for `e2e beacon` (docs/e2e-local.md, «Ноутбук вместо второго телефона»): the bot
// on the laptop plays through the app's client code, and this helper is its phone's Bluetooth. It advertises the
// bot's token the way an iPhone hider does (the game's service with the token as the name, which Android and iOS
// phones both scan for) and reports every radar token it hears: an Android hider's service data, an iPhone hider's
// name (bare, or after the first apps' «hv»), a seeker's iBeacon frame when macOS shows it.
//
// Lines on stdin: `advertise <token>`, `stop`. Lines on stdout: `state on|off|denied|unsupported`,
// `heard <token> <rssi>`, `log <text>`. Closing stdin ends it. Built by e2e/mac-beacon/run.sh.

import CoreBluetooth
import Foundation

let serviceUUID = CBUUID(string: "7A0B8D2E-4C1F-4E6A-9B3D-2F5E8C1A7D10")
let namePrefix = "hv"
let minRssi = -110

func say(_ line: String) {
    print(line)
    fflush(stdout)
}

func isToken(_ text: String) -> Bool {
    text.count == 8 && text.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
}

func hex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}

final class Beacon: NSObject, CBPeripheralManagerDelegate, CBCentralManagerDelegate {
    private var peripheral: CBPeripheralManager!
    private var central: CBCentralManager!
    private var token: String?
    private var lastState = ""

    override init() {
        super.init()
        peripheral = CBPeripheralManager(delegate: self, queue: nil)
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func advertise(_ token: String?) {
        self.token = token
        restartAdvertising()
    }

    // Like the app: macOS drops startAdvertising until the manager says it is powered on.
    private func restartAdvertising() {
        guard peripheral.state == .poweredOn else { return }
        peripheral.stopAdvertising()
        guard let token = token else { return }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [serviceUUID],
            CBAdvertisementDataLocalNameKey: token,
        ])
    }

    private func reportState() {
        let states = [peripheral.state, central.state]
        let state: String
        if states.allSatisfy({ $0 == .poweredOn }) {
            state = "on"
        } else if states.contains(.unauthorized) {
            state = "denied"
        } else if states.contains(.unsupported) {
            state = "unsupported"
        } else {
            state = "off"
        }
        if state != lastState {
            lastState = state
            say("state \(state)")
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        reportState()
        restartAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            say("log advertising failed: \(error.localizedDescription)")
        } else {
            say("log advertising \(token ?? "")")
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        reportState()
        // Everything, not only the game's service: a seeker's iBeacon frame has no service in it.
        if central.state == .poweredOn {
            central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // 127: no reading.
        let rssi = RSSI.intValue
        guard rssi < 0, rssi >= minRssi, let token = tokenIn(advertisementData) else { return }
        say("heard \(token) \(rssi)")
    }

    private func tokenIn(_ advertisement: [String: Any]) -> String? {
        // An Android hider: the token as the service's data.
        if let serviceData = advertisement[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
           let data = serviceData[serviceUUID] {
            let token = hex(data)
            if isToken(token) { return token }
        }
        // A seeker's iBeacon: Apple's company id, type 0x02, length 0x15, the game's UUID, major, minor, power.
        if let frame = advertisement[CBAdvertisementDataManufacturerDataKey] as? Data {
            let bytes = [UInt8](frame)
            let uuid = withUnsafeBytes(of: UUID(uuidString: serviceUUID.uuidString)!.uuid) { [UInt8]($0) }
            if bytes.count >= 25, bytes[0] == 0x4C, bytes[1] == 0x00, bytes[2] == 0x02, bytes[3] == 0x15,
               Array(bytes[4..<20]) == uuid {
                return hex(Data(bytes[20..<24]))
            }
        }
        // An iPhone hider on the screen: the token as the name, only with the game's service next to it.
        let services = advertisement[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        if services.contains(serviceUUID), let name = advertisement[CBAdvertisementDataLocalNameKey] as? String {
            let token = name.hasPrefix(namePrefix) ? String(name.dropFirst(namePrefix.count)) : name
            if isToken(token) { return token }
        }
        return nil
    }
}

let beacon = Beacon()
DispatchQueue.global().async {
    while let line = readLine() {
        let parts = line.split(separator: " ").map(String.init)
        DispatchQueue.main.async {
            switch parts.first {
            case "advertise" where parts.count > 1 && isToken(parts[1]): beacon.advertise(parts[1])
            case "stop": beacon.advertise(nil)
            default: say("log unknown command: \(line)")
            }
        }
    }
    // The bot is gone.
    exit(0)
}
RunLoop.main.run()

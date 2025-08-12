import 'dart:async';
import 'package:battery_plus/battery_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _channel = MethodChannel('charge_guard/root');

Future<bool> _isRoot() async =>
    (await _channel.invokeMethod<bool>('isRoot')) ?? false;
Future<String> _probeRoot() async =>
    (await _channel.invokeMethod<String>('probeRoot')) ?? 'no output';
Future<String> _run(String cmd) async =>
    (await _channel.invokeMethod<String>('runShell', {'cmd': cmd})) ?? '';
Future<String> _readNode(String path) async =>
    (await _channel.invokeMethod<String>('readNode', {'path': path})) ?? '';
Future<bool> _writeNode(String path, String value) async =>
    (await _channel.invokeMethod<bool>('writeNode', {
      'path': path,
      'value': value,
    })) ??
    false;

Future<void> _startService(int cap, int hyst, String node) => _channel
    .invokeMethod('startService', {'cap': cap, 'hyst': hyst, 'node': node});
Future<void> _stopService() => _channel.invokeMethod('stopService');

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ChargeGuardApp());
}

class ChargeGuardApp extends StatelessWidget {
  const ChargeGuardApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Charge Guard',
      theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final battery = Battery();
  Timer? _timer;
  int _level = 0;
  bool _charging = false;

  bool _svcEnabled = false;
  int _cap = 80;
  int _hyst = 5;
  final _nodeCtrl = TextEditingController();
  String _logs = '';
  bool _root = false;

  final SharedPreferencesAsync _prefs = SharedPreferencesAsync();

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    _root = await _isRoot();
    await _loadPrefs();
    if (mounted) setState(() {});
    _initBatteryWatcher();
  }

  Future<void> _loadPrefs() async {
    _svcEnabled = (await _prefs.getBool('svc_enabled')) ?? false;
    _cap = (await _prefs.getInt('cap')) ?? 80;
    _hyst = (await _prefs.getInt('hyst')) ?? 5;
    _nodeCtrl.text = (await _prefs.getString('nodePref')) ?? '';
    if (_svcEnabled) {
      await _startService(_cap, _hyst, _nodeCtrl.text);
    }
  }

  void _initBatteryWatcher() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) async {
      final lvl = await battery.batteryLevel;
      final state = await battery.batteryState;
      if (!mounted) return;
      setState(() {
        _level = lvl;
        _charging =
            state == BatteryState.charging || state == BatteryState.full;
      });
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _nodeCtrl.dispose();
    super.dispose();
  }

  Future<void> _savePrefs() async {
    await _prefs.setBool('svc_enabled', _svcEnabled);
    await _prefs.setInt('cap', _cap);
    await _prefs.setInt('hyst', _hyst);
    await _prefs.setString('nodePref', _nodeCtrl.text);
  }

  void _log(String s) => setState(() {
    _logs = '${DateTime.now()}: $s\n$_logs';
    print("Re-check root (verbose) => $_logs");
  });

  Future<void> _toggleService(bool on) async {
    if (!_root) {
      _log('Root not available.');
      return;
    }
    setState(() => _svcEnabled = on);
    await _savePrefs();
    if (on) {
      await _startService(_cap, _hyst, _nodeCtrl.text);
      _log('Service started.');
    } else {
      await _stopService();
      _log('Service stopped.');
    }
  }

  Future<void> _probeCommonNodes() async {
    const nodes = [
      "/sys/class/power_supply/battery/charging_enabled",
      "/sys/class/power_supply/battery/store_mode",
      "/sys/class/power_supply/battery/batt_slate_mode",
      "/sys/class/power_supply/battery/input_suspend",
    ];
    final found = <String>[];
    for (final n in nodes) {
      final out = await _readNode(n);
      if (out.isNotEmpty &&
          !out.contains("ERROR") &&
          !out.contains("No such file")) {
        found.add("$n => ${out.trim().split('\n').first}");
      }
    }
    _log(
      found.isEmpty
          ? "No common nodes readable. Use a custom path."
          : "Detected:\n${found.join('\n')}",
    );
  }

  Future<void> _quickEnable(bool enable) async {
    if (_nodeCtrl.text.isEmpty) {
      _log('Set a node path first (or run Probe).');
      return;
    }
    final base = _nodeCtrl.text.trim();
    final inverted =
        base.endsWith("input_suspend") ||
        base.endsWith("store_mode") ||
        base.endsWith("batt_slate_mode");
    final value = inverted ? (enable ? "0" : "1") : (enable ? "1" : "0");
    final ok = await _writeNode(base, value);
    _log(ok ? "Charging ${enable ? "ENABLED" : "DISABLED"}" : "Write failed.");
  }

  Future<void> _verboseRootCheck() async {
    final report = await _probeRoot();
    _log("Root probe report:\n$report");
    // After probe, our simple check may become true if Magisk prompt just appeared
    final now = await _isRoot();
    setState(() => _root = now);
  }

  Future<void> _requestRootAccess() async {
    try {
      final result = await _channel.invokeMethod('runShell', {'cmd': 'id'});
      _log('Root access granted: $result');
    } catch (e) {
      _log('Error requesting root: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Charge Guard')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Row(
            children: [
              Icon(
                _charging ? Icons.flash_on : Icons.flash_off,
                color: _charging ? Colors.orange : Colors.grey,
              ),
              const SizedBox(width: 8),
              Text(
                'Battery: $_level%  •  ${_charging ? "Charging" : "Not charging"}',
              ),
              const Spacer(),
              Chip(
                label: InkWell(
                  onTap: _requestRootAccess,
                  child: Text(_root ? "root" : "no rdddoot"),
                ),
                backgroundColor: _root
                    ? Colors.green.shade100
                    : Colors.red.shade100,
              ),
              Chip(
                label: Text(_root ? "root" : "no root"),
                backgroundColor: _root
                    ? Colors.green.shade100
                    : Colors.red.shade100,
              ),
            ],
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              ElevatedButton.icon(
                onPressed: _verboseRootCheck,
                icon: const Icon(Icons.security),
                label: const Text('Re-check root (verbose)'),
              ),
              ElevatedButton.icon(
                onPressed: () => _run("id").then((o) => _log("shell id:\n$o")),
                icon: const Icon(Icons.code),
                label: const Text('Run id (root shell)'),
              ),
            ],
          ),
          const Divider(height: 24),

          TextField(
            controller: _nodeCtrl,
            decoration: const InputDecoration(
              labelText: 'Sysfs node path',
              hintText: '/sys/class/power_supply/battery/charging_enabled',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            children: [
              ElevatedButton.icon(
                onPressed: _probeCommonNodes,
                icon: const Icon(Icons.search),
                label: const Text('Probe common nodes'),
              ),
              ElevatedButton.icon(
                onPressed: () => _quickEnable(true),
                icon: const Icon(Icons.play_arrow),
                label: const Text('Enable charge'),
              ),
              ElevatedButton.icon(
                onPressed: () => _quickEnable(false),
                icon: const Icon(Icons.stop),
                label: const Text('Disable charge'),
              ),
            ],
          ),
          const Divider(height: 32),
          Row(
            children: [
              const Text('Charge cap'),
              Expanded(
                child: Slider(
                  min: 50,
                  max: 100,
                  divisions: 50,
                  label: '$_cap%',
                  value: _cap.toDouble(),
                  onChanged: (v) => setState(() => _cap = v.round()),
                ),
              ),
              Text('$_cap%'),
            ],
          ),
          Row(
            children: [
              const Text('Hysteresis'),
              Expanded(
                child: Slider(
                  min: 2,
                  max: 20,
                  divisions: 18,
                  label: '$_hyst%',
                  value: _hyst.toDouble(),
                  onChanged: (v) => setState(() => _hyst = v.round()),
                ),
              ),
              Text('$_hyst%'),
            ],
          ),
          SwitchListTile(
            title: const Text('Run background service (apply cap)'),
            subtitle: const Text('Persists across reboots'),
            value: _svcEnabled,
            onChanged: (v) => _toggleService(v),
          ),
          const SizedBox(height: 8),
          ElevatedButton.icon(
            onPressed: () async {
              await _savePrefs();
              if (_svcEnabled) await _startService(_cap, _hyst, _nodeCtrl.text);
              _log('Settings saved.');
            },
            icon: const Icon(Icons.save),
            label: const Text('Save'),
          ),
          const SizedBox(height: 16),
          const Text('Logs'),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.black.withOpacity(.05),
              borderRadius: BorderRadius.circular(8),
            ),
            height: 220,
            child: SingleChildScrollView(
              child: Text(
                _logs,
                style: const TextStyle(fontFamily: 'monospace'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

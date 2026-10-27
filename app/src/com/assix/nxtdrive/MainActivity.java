package com.assix.nxtdrive;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NXT Drive - simple remote control for a LEGO MINDSTORMS NXT brick.
 *
 * Talks Bluetooth Classic SPP (RFCOMM) to the brick and sends LEGO
 * MessageWrite telegrams to mailbox 1, which the on-brick bt_drive
 * program interprets as: F/B/L/R/S (forward/back/back/spin/stop).
 *
 * Telegram format: 06 00 | 80 09 00 02 <letter> 00
 *   06 00  = little-endian length (6 bytes follow)
 *   80     = direct command, no reply
 *   09     = MessageWrite
 *   00     = mailbox 1 (protocol index 0)
 *   02     = message size (letter + NUL)
 *   letter = 'F' 0x46, 'B' 0x42, 'L' 0x4C, 'R' 0x52, 'S' 0x53
 */
public class MainActivity extends Activity {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int REQ_BT_CONNECT = 1001;

    private static final byte[] CMD_FORWARD =
            {0x06, 0x00, (byte) 0x80, 0x09, 0x00, 0x02, 0x46, 0x00};
    private static final byte[] CMD_BACKWARD =
            {0x06, 0x00, (byte) 0x80, 0x09, 0x00, 0x02, 0x42, 0x00};
    private static final byte[] CMD_LEFT =
            {0x06, 0x00, (byte) 0x80, 0x09, 0x00, 0x02, 0x4C, 0x00};
    private static final byte[] CMD_RIGHT =
            {0x06, 0x00, (byte) 0x80, 0x09, 0x00, 0x02, 0x52, 0x00};
    private static final byte[] CMD_STOP =
            {0x06, 0x00, (byte) 0x80, 0x09, 0x00, 0x02, 0x53, 0x00};

    private BluetoothAdapter adapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    private Spinner deviceSpinner;
    private Button connectButton;
    private TextView statusText;
    private Button btnForward, btnBackward, btnLeft, btnRight, btnStop;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private BluetoothSocket socket;
    private OutputStream out;
    private volatile boolean connected = false;
    private boolean pendingConnect = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceSpinner = findViewById(R.id.deviceSpinner);
        connectButton = findViewById(R.id.connectButton);
        statusText = findViewById(R.id.statusText);
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStop = findViewById(R.id.btnStop);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            setStatus("This device has no Bluetooth");
            connectButton.setEnabled(false);
            return;
        }

        spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new ArrayList<String>());
        deviceSpinner.setAdapter(spinnerAdapter);

        if (needsConnectPermission()) {
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
        } else {
            populateDevices();
        }

        connectButton.setOnClickListener(v -> {
            if (connected) {
                disconnect();
            } else {
                startConnect();
            }
        });

        wireDriveButton(btnForward, CMD_FORWARD);
        wireDriveButton(btnBackward, CMD_BACKWARD);
        wireDriveButton(btnLeft, CMD_LEFT);
        wireDriveButton(btnRight, CMD_RIGHT);
        btnStop.setOnClickListener(v -> send(CMD_STOP));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!needsConnectPermission()) {
            populateDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_CONNECT) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                populateDevices();
                if (pendingConnect) {
                    pendingConnect = false;
                    startConnect();
                }
            } else {
                pendingConnect = false;
                setStatus("Bluetooth permission denied");
            }
        }
    }

    private boolean needsConnectPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void populateDevices() {
        devices.clear();
        List<String> labels = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    devices.add(d);
                    String name;
                    try {
                        name = d.getName();
                    } catch (SecurityException e) {
                        name = null;
                    }
                    labels.add((name != null ? name : "Unknown") + "  (" + d.getAddress() + ")");
                }
            }
        } catch (SecurityException e) {
            // Permission missing; labels stay empty.
        }
        if (labels.isEmpty()) {
            labels.add("No paired devices");
        }
        spinnerAdapter.clear();
        spinnerAdapter.addAll(labels);
        spinnerAdapter.notifyDataSetChanged();
    }

    private void startConnect() {
        if (needsConnectPermission()) {
            pendingConnect = true;
            requestPermissions(
                    new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT_CONNECT);
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth is off - turning it on...");
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception e) {
                setStatus("Bluetooth is off - turn it on in Settings");
            }
            return;
        }
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            setStatus("No paired NXT - pair it in Android Settings first");
            return;
        }
        final BluetoothDevice device = devices.get(pos);
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }
        final String label = name != null ? name : device.getAddress();

        setStatus("Connecting to " + label + "...");
        connectButton.setEnabled(false);
        deviceSpinner.setEnabled(false);

        io.execute(() -> {
            BluetoothSocket s = null;
            try {
                s = device.createRfcommSocketToServiceRecord(SPP_UUID);
                try {
                    adapter.cancelDiscovery();
                } catch (SecurityException ignored) {
                }
                s.connect();
                socket = s;
                out = s.getOutputStream();
                connected = true;
                runOnUiThread(() -> {
                    setStatus("Connected to " + label);
                    connectButton.setText("Disconnect");
                    connectButton.setEnabled(true);
                    setDpadEnabled(true);
                });
            } catch (Exception e) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
                socket = null;
                out = null;
                connected = false;
                final String msg = e.getMessage();
                runOnUiThread(() -> {
                    setStatus("Connect failed" + (msg != null ? ": " + msg : ""));
                    connectButton.setEnabled(true);
                    deviceSpinner.setEnabled(true);
                });
            }
        });
    }

    private void disconnect() {
        connected = false;
        setDpadEnabled(false);
        connectButton.setText("Connect");
        deviceSpinner.setEnabled(true);
        setStatus("Disconnected");
        final BluetoothSocket s = socket;
        final OutputStream o = out;
        socket = null;
        out = null;
        io.execute(() -> {
            if (s != null) {
                if (o != null) {
                    try {
                        o.write(CMD_STOP);
                        o.flush();
                    } catch (IOException ignored) {
                    }
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void send(final byte[] telegram) {
        if (!connected) {
            return;
        }
        io.execute(() -> {
            OutputStream o = out;
            if (o == null) {
                return;
            }
            try {
                synchronized (MainActivity.this) {
                    o.write(telegram);
                    o.flush();
                }
            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (connected) {
                        setStatus("Connection lost");
                        disconnect();
                    }
                });
            }
        });
    }

    private void wireDriveButton(Button button, final byte[] telegram) {
        button.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    send(telegram);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    send(CMD_STOP);
                    break;
                default:
                    break;
            }
            return false; // let the button show its pressed state
        });
    }

    private void setDpadEnabled(boolean enabled) {
        btnForward.setEnabled(enabled);
        btnBackward.setEnabled(enabled);
        btnLeft.setEnabled(enabled);
        btnRight.setEnabled(enabled);
        btnStop.setEnabled(enabled);
    }

    private void setStatus(final String text) {
        if (statusText != null) {
            statusText.setText(text);
        }
    }

    @Override
    protected void onDestroy() {
        final BluetoothSocket s = socket;
        final OutputStream o = out;
        socket = null;
        out = null;
        connected = false;
        if (s != null) {
            io.execute(() -> {
                if (o != null) {
                    try {
                        o.write(CMD_STOP);
                        o.flush();
                    } catch (IOException ignored) {
                    }
                }
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            });
        }
        io.shutdown();
        super.onDestroy();
    }
}

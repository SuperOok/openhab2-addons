/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.vallox.internal.serial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.openhab.binding.vallox.internal.exceptions.InsufficientDataException;
import org.openhab.binding.vallox.internal.exceptions.InvalidRecepientException;
import org.openhab.binding.vallox.internal.exceptions.MalformedTelegramException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main API class for connecting to a vallox central venting unit
 * via a RS485-to-Ethernet bridge. The bridge should be configured
 * such that it behaves as a TCP server and tunnels input and output
 * streams to the RS485 vallox interface.
 *
 * Main telegram specification taken from C-Code from
 * https://github.com/windkh/valloxserial
 *
 * @author Hauke Fuhrmann - Initial contribution
 *
 */
public class ValloxSerialInterface {

    public static final int MINUTE = 60000;

    private final Logger logger = LoggerFactory.getLogger(ValloxSerialInterface.class);

    byte senderID = ValloxProtocol.ADDRESS_PANEL8; // we send commands in the name of panel8 (29)
    byte receiverID = ValloxProtocol.ADDRESS_PANEL1; // we always listen for the telegrams between the master and the
                                                     // panel1!

    private OutputStream outputStream;
    private InputStream inputStream;
    private Socket socket;
    private ScheduledExecutorService scheduler;
    private boolean shutdownListening;

    private final List<ValueChangeListener> valueListener = new ArrayList<>();;
    private final List<StatusChangeListener> statusListener = new ArrayList<>();;
    private ValloxStore vallox = new ValloxStore();

    private String host;
    private int port;

    public ValloxSerialInterface(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Get a bean with all variable states of the vallox installations. Variables
     * will be updated after listening has started. Initialially, variables are initialized with
     * 0 resp. false and might only get updated if the vallox sends updates. Need to
     * trigger a variable poll explicitly.
     *
     * @return the variable store
     */
    public ValloxStore getValloxStore() {
        return vallox;
    }

    public List<ValueChangeListener> getValueListener() {
        return valueListener;
    }

    public List<StatusChangeListener> getStatusListener() {
        return statusListener;
    }

    /**
     * Connect to a TCP providing host that bridges the serial interface of the
     * vallox installation to a TCP server.
     *
     * @param host Hostname or IP Adress of the tcp-server
     * @param port port of the server
     * @throws UnknownHostException
     * @throws IOException
     */
    public void connect(String host, int port) throws UnknownHostException, IOException {
        this.host = host;
        this.port = port;
        socket = new Socket(host, port);
        outputStream = socket.getOutputStream();
        inputStream = socket.getInputStream();
        logger.debug("Connected to {}:{}", host, port);
        for (StatusChangeListener l : this.getStatusListener()) {
            l.statusChanged(ThingStatus.ONLINE, ThingStatusDetail.NONE, null);
        }

    }

    public void reconnect() throws UnknownHostException, IOException {
        logger.debug("Trying to reconnect: {}:{}", host, port);
        connect(host, port);
    }

    public void close() {
        try {
            if (socket != null) {
                socket.close();
                logger.debug("Closed {}:{}", socket.getInetAddress(), socket.getPort());
            }
        } catch (IOException e) {
            logger.error("Exception while closing the connection.", e);
        }
    }

    /**
     * Send a poll request to the vallox. Denoting the property will request a value update
     * from the vallox. It will answer with a corresponding telegram with the updated
     * value. However, this answer is an asynchronous callback and it cannot handle
     * multiple requests in too short time. It will then only process the latest
     * request correctly and answer a lot of garbage bytes before that.
     *
     * @param prop
     * @throws IOException
     */
    public void sendPoll(ValloxProperty prop) throws IOException {
        if (scheduler == null || scheduler.isTerminated()) {
            logger.error("Poll requested while no-one is listening for answers!");
        }
        Variable v = null;
        switch (prop) {
            case AVERAGE_EFFICIENCY:
            case IN_EFFICIENCY:
            case OUT_EFFICIENCY:
                // calculated
                break;
            case ADJUSTMENT_INTERVAL_MINUTES:
            case AUTOMATIC_HUMIDITY_LEVEL_SEEKER_STATE:
            case BOOST_SWITCH_MODE:
            case CASCADE_ADJUST:
            case RADIATOR_TYPE:
            case PROGRAM:
                v = Variable.PROGRAM;
                break;
            case MAX_SPEED_LIMIT_MODE:
            case PROGRAM_2:
                v = Variable.PROGRAM2;
                break;
            case CO2_ADJUST_STATE:
            case POWER_STATE:
            case HUMIDITY_ADJUST_STATE:
            case HEATING_STATE:
            case FILTER_GUARD_INDICATOR:
            case HEATING_INDICATOR:
            case FAULT_INDICATOR:
            case SERVICE_REMINDER_INDICATOR:
            case SELECT_STATUS:
                v = Variable.SELECT;
                break;
            case POST_HEATING_ON:
                v = Variable.IOPORT_MULTI_PURPOSE_1;
                break;
            case DAMPER_MOTOR_POSITION:
            case FAULT_SIGNAL_RELAY_CLOSED:
            case SUPPLY_FAN_OFF:
            case PRE_HEATING_ON:
            case EXHAUST_FAN_OFF:
            case FIRE_PLACE_BOOSTER_CLOSED:
                v = Variable.IOPORT_MULTI_PURPOSE_2;
                break;
            case BASIC_HUMIDITY_LEVEL:
                v = Variable.BASIC_HUMIDITY_LEVEL;
                break;
            case CELL_DEFROSTING_THRESHOLD:
                v = Variable.CELL_DEFROSTING;
                break;
            case CO2_HIGH:
                v = Variable.CO2_HIGH;
                break;
            case CO2_LOW:
                v = Variable.CO2_LOW;
                break;
            case CO2_SETPOINT_HIGH:
                v = Variable.CO2_SET_POINT_UPPER;
                break;
            case CO2_SETPOINT_LOW:
                v = Variable.CO2_SET_POINT_LOWER;
                break;
            case DC_FAN_INPUT_ADJUSTMENT:
                v = Variable.DC_FAN_INPUT_ADJUSTMENT;
                break;
            case DC_FAN_OUTPUT_ADJUSTMENT:
                v = Variable.DC_FAN_OUTPUT_ADJUSTMENT;
                break;
            case FAN_SPEED:
                v = Variable.FAN_SPEED;
                break;
            case FAN_SPEED_MAX:
                v = Variable.FAN_SPEED_MAX;
                break;
            case FAN_SPEED_MIN:
                v = Variable.FAN_SPEED_MIN;
                break;
            case HEATING_SETPOINT:
                v = Variable.HEATING_SET_POINT;
                break;
            case HRC_BYPASS_THRESHOLD:
                v = Variable.HRC_BYPASS;
                break;
            case HUMIDITY:
                v = Variable.HUMIDITY;
                break;
            case HUMIDITY_SENSOR_1:
                v = Variable.HUMIDITY_SENSOR1;
                break;
            case HUMIDITY_SENSOR_2:
                v = Variable.HUMIDITY_SENSOR2;
                break;
            case INCOMMING_CURRENT:
                v = Variable.CURRENT_INCOMMING;
                break;
            case INPUT_FAN_STOP_THRESHOLD:
                v = Variable.INPUT_FAN_STOP;
                break;
            case IO_PORT_MULTI_PURPOSE_1:
                v = Variable.IOPORT_MULTI_PURPOSE_1;
                break;
            case IO_PORT_MULTI_PURPOSE_2:
                v = Variable.IOPORT_MULTI_PURPOSE_2;
                break;
            case LAST_ERROR_NUMBER:
                v = Variable.LAST_ERROR_NUMBER;
                break;
            case PRE_HEATING_SETPOINT:
                v = Variable.PRE_HEATING_SET_POINT;
                break;
            case SERVICE_REMINDER:
                v = Variable.SERVICE_REMINDER;
                break;
            case TEMP_EXHAUST:
                v = Variable.TEMP_EXHAUST;
                break;
            case TEMP_INCOMMING:
                v = Variable.TEMP_INCOMMING;
                break;
            case TEMP_INSIDE:
                v = Variable.TEMP_INSIDE;
                break;
            case TEMP_OUTSIDE:
                v = Variable.TEMP_OUTSIDE;
                break;
            default:
                break;
        }
        if (v != null) {
            logger.debug("Sending Poll request for property {} with variable {}", prop, v);
            send(Variable.POLL.getKey(), v.getKey());
        }
    }

    public void send(byte variable, byte value, byte destination) throws IOException {
        byte[] telegram = new byte[ValloxProtocol.LENGTH];

        telegram[0] = ValloxProtocol.DOMAIN;
        telegram[1] = senderID;
        telegram[2] = destination;
        telegram[3] = variable;
        telegram[4] = value;
        telegram[5] = calculateChecksum(telegram);

        if (logger.isDebugEnabled()) { // avoid executing the byteToHex if no debug mode is active
            logger.debug("sending telegram: {} {} {} {} {} {}", Telegram.byteToHex(telegram[0]),
                    Telegram.byteToHex(telegram[1]), Telegram.byteToHex(telegram[2]), Telegram.byteToHex(telegram[3]),
                    Telegram.byteToHex(telegram[4]), Telegram.byteToHex(telegram[5]));
        }
        serialWrite(telegram);
    }

    /**
     * Send one telegram to the master unit.
     * Example: send(Variable.FAN_SPEED.key, Telegram.convertBackFanSpeed((byte)4));
     *
     * @param variable one of the Variable keys in {@link Variable}}
     * @param value the right binary coded value (see {@link Telegram} for some conversions)
     * @throws IOException
     */
    public void send(byte variable, byte value) throws IOException {
        send(variable, value, ValloxProtocol.ADDRESS_MASTER);
    }

    private void serialWrite(byte[] telegram) {
        try {
            if (outputStream != null) {
                outputStream.write(telegram);
                outputStream.flush();
            }
        } catch (IOException e) {
            String msg = "Exception writing to vallox.";
            logger.error(msg, e);
            for (StatusChangeListener l : this.getStatusListener()) {
                l.statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, msg + " " + e.toString());
            }
        }
    }

    static byte calculateChecksum(byte[] pTelegram) {
        int checksum = 0;
        for (byte i = 0; i < pTelegram.length - 1; i++) {
            checksum += pTelegram[i];
        }
        return (byte) (checksum % 256);
    }

    /**
     * Start listening to telegrams on the serial interface. Stop if requested.
     */
    public void startListening() {
        shutdownListening = false;
        scheduler.submit(() -> {
            long sleep = 0;
            while (!shutdownListening && !Thread.interrupted()) {
                try {
                    Telegram telegram = receive();
                    logger.trace("Received message: {}", telegram);
                    telegram.updateStore(vallox, valueListener);
                } catch (MalformedTelegramException e) {
                    logger.warn("Issue receiving telegram. Discarding.", e);
                } catch (InvalidRecepientException e) {
                    logger.debug("Issue receiving telegram. Discarding.", e);
                } catch (InsufficientDataException e) {
                    // telegram too short, wait for more data, but ignore
                    sleep = 200;
                } catch (IOException e) {
                    logger.error("Exception reading input stream.", e);
                    // in case of low-level errors, retry, but not too quickly
                    sleep = 2000;
                } finally {
                    if (sleep > 0) {
                        try {
                            Thread.sleep(sleep);
                            sleep = 0; // reset sleep for the next telegram
                        } catch (InterruptedException e1) {
                            logger.error("Telegram listening thread interrupted.");
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        });
        logger.debug("Start listening to vallox telegrams!");
    }

    /**
     * Start a heartbeat thread that regularly polls a vallox variable and
     * automatically reconnects if it fails.
     */
    public void startHeartbeat() {
        final ValloxSerialInterface vallox = this;
        Runnable reconnecter = new Runnable() {
            @Override
            public void run() {
                if (!shutdownListening) {
                    try {
                        sendPoll(ValloxProperty.SELECT_STATUS);
                    } catch (Exception e) {
                        String msg = "Exception sending heartbeat poll.";
                        logger.error(msg, e);
                        for (StatusChangeListener l : vallox.getStatusListener()) {
                            l.statusChanged(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                    msg + " " + e.toString());
                        }
                        try {
                            vallox.reconnect();
                        } catch (IOException e1) {
                            logger.error("Exception reconnecting vallox.", e1);
                        }
                    }
                }
            }
        };
        scheduler.scheduleWithFixedDelay(reconnecter, 1, 1, TimeUnit.MINUTES);
        logger.debug("Start heartbeat check to vallox!");
    }

    /**
     * Read one telegram.
     *
     * @return the received telegram or null if no telegram could be read
     * @throws IOException
     * @throws InterruptedException
     * @throws InsufficientDataException
     * @throws MalformedTelegramException
     * @throws InvalidRecepientException
     */
    private Telegram receive()
            throws IOException, InsufficientDataException, MalformedTelegramException, InvalidRecepientException {
        int available = inputStream.available();
        if (available < ValloxProtocol.LENGTH) {
            throw new InsufficientDataException("Not enough bytes available for valid Vallox telegram.");
        }
        int domain = inputStream.read();
        if (domain != ValloxProtocol.DOMAIN) {
            throw new MalformedTelegramException(
                    "Received Telegram has no Domain byte, ignoring telegram: " + Telegram.byteToHex((byte) domain));
        }

        // now we're happy -> read the telegram
        int sender = inputStream.read();
        int receiver = inputStream.read();
        int command = inputStream.read();
        int arg = inputStream.read();
        int checksum = inputStream.read();
        int computedChecksum = (domain + sender + receiver + command + arg) & 0x00ff;

        if (checksum != computedChecksum) {
            throw new MalformedTelegramException("Received Telegram has invalid checksum, ignoring telegram.");
        }

        // only read telegrams that are for us
        if (receiver == receiverID || receiver == senderID || receiver == ValloxProtocol.ADDRESS_PANELS) {
            return new Telegram((byte) sender, (byte) receiver, (byte) command, (byte) arg);
        }
        String msg = MessageFormat.format(
                "Ignoring telegram not for us: sender: {0}, receiver: {1}, command: {2}, arg: {3}", sender, receiver,
                command, arg);
        throw new InvalidRecepientException(msg);
    }

    /**
     * Stop listening to telegrams on the serial interface. Requests all
     * reading threads to stop. If they do not terminate within 5 seconds, the
     * threads get terminated.
     */
    public void stopListening() {
        if (scheduler != null) {
            try {
                logger.debug("attempt to shutdown listener");
                shutdownListening = true;
                scheduler.shutdown();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("Listener Thread interrupted");
                Thread.currentThread().interrupt();
            } finally {
                if (!scheduler.isTerminated()) {
                    logger.warn("Listener Thread cancel non-finished tasks");
                }
                scheduler.shutdownNow();
                logger.debug("shutdown of vallox listener finished");
            }
        }
    }

}

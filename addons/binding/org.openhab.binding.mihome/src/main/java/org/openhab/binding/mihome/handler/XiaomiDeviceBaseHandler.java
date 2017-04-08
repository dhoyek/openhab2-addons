/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.mihome.handler;

import static org.openhab.binding.mihome.XiaomiGatewayBindingConstants.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.smarthome.core.items.Item;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.mihome.internal.XiaomiItemUpdateListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link XiaomiDeviceBaseHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Patrick Boos - Initial contribution
 * @author Kuba Wolanin - Added voltage and low battery report
 * @author Dimalo - Added cube rotation, heartbeat and voltage handling, configurable window and motion delay
 */
public abstract class XiaomiDeviceBaseHandler extends BaseThingHandler implements XiaomiItemUpdateListener {

    public final static Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<>(
            Arrays.asList(THING_TYPE_GATEWAY, THING_TYPE_SENSOR_HT, THING_TYPE_SENSOR_MOTION, THING_TYPE_SENSOR_SWITCH,
                    THING_TYPE_SENSOR_MAGNET, THING_TYPE_SENSOR_PLUG, THING_TYPE_SENSOR_CUBE));

    private static final long ONLINE_TIMEOUT = 2 * 60 * 60 * 1000; // 2 hours

    private JsonParser parser = new JsonParser();

    private XiaomiBridgeHandler bridgeHandler;

    String itemId;

    Logger logger = LoggerFactory.getLogger(XiaomiDeviceBaseHandler.class);

    public XiaomiDeviceBaseHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        final String configItemId = (String) getConfig().get(ITEM_ID);
        if (configItemId != null) {
            itemId = configItemId;
        }
        // schedule init with random delay between 200ms and 1sec
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                updateThingStatus();
            }
        }, (int) Math.max((Math.random() * 1000), 200), TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        logger.debug("Handler disposes. Unregistering listener.");
        if (itemId != null) {
            XiaomiBridgeHandler bridgeHandler = getXiaomiBridgeHandler();
            if (bridgeHandler != null) {
                bridgeHandler.unregisterItemListener(this);
                this.bridgeHandler = null;
            }
            itemId = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // TODO somehow it seems that this is called as well with LAST KNOWN STATE when openhab gets started. Can this
        // be turned off somehow?
        logger.debug("Device {} on channel {} received command {}", itemId, channelUID, command);
        if (command.toString().toLowerCase().equals("refresh")) {
            return;
        }
        execute(channelUID, command);
    }

    @Override
    public void onItemUpdate(String sid, String command, JsonObject message) {
        if (itemId != null && itemId.equals(sid)) {
            logger.debug("Item got update: {}", message.toString());
            JsonObject data = parser.parse(message.get("data").getAsString()).getAsJsonObject();
            parseCommand(command, data);
            updateThingStatus();
        }
    }

    @Override
    public String getItemId() {
        return itemId;
    }

    /**
     * @param sid
     * @param command
     * @param data
     */
    void parseCommand(String command, JsonObject data) {
        if (command.equals("report")) {
            parseReport(data);
        } else if (command.equals("heartbeat") || command.equals("read_ack")) {
            if (data.get("voltage") != null) {
                Integer voltage = data.get("voltage").getAsInt();
                updateState(CHANNEL_VOLTAGE, new DecimalType(voltage));
                if (voltage < 2800) {
                    triggerChannel(CHANNEL_BATTERY_LOW, "LOW");
                }
            }
        } else {
            logger.debug("Device {} got unknown command {}", itemId, command);
        }
    }

    /**
     * @param channelUID
     * @param command
     */
    abstract void execute(ChannelUID channelUID, Command command);

    /**
     * @param data
     */
    abstract void parseReport(JsonObject data);

    private void updateThingStatus() {
        if (itemId != null) {
            // note: this call implicitly registers our handler as a listener on the bridge, if it's not already
            if (getXiaomiBridgeHandler() != null) {
                Bridge bridge = getBridge();
                ThingStatus bridgeStatus = (bridge == null) ? null : bridge.getStatus();
                if (bridgeStatus == ThingStatus.ONLINE) {
                    ThingStatus itemStatus = getThing().getStatus();
                    boolean hasItemActivity = getXiaomiBridgeHandler().hasItemActivity(itemId, ONLINE_TIMEOUT);
                    ThingStatus newStatus = hasItemActivity ? ThingStatus.ONLINE : ThingStatus.OFFLINE;

                    if (!newStatus.equals(itemStatus)) {
                        updateStatus(newStatus);

                        // TODO initialize properties?
                        // initializeProperties();
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
                }
            } else {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.HANDLER_REGISTERING_ERROR);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR);
        }
    }

    synchronized XiaomiBridgeHandler getXiaomiBridgeHandler() {
        if (this.bridgeHandler == null) {
            Bridge bridge = getBridge();
            if (bridge == null) {
                return null;
            }
            ThingHandler handler = bridge.getHandler();
            if (handler instanceof XiaomiBridgeHandler) {
                this.bridgeHandler = (XiaomiBridgeHandler) handler;
                this.bridgeHandler.registerItemListener(this);
            } else {
                return null;
            }
        }
        return this.bridgeHandler;
    }

    synchronized Item getItemInChannel(String channel) {
        Iterator<Item> iterator = linkRegistry.getLinkedItems(thing.getChannel(channel).getUID()).iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }
}

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

import org.eclipse.smarthome.core.items.Item;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.HSBType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.State;
import org.openhab.binding.mihome.internal.ColorUtil;

import com.google.gson.JsonObject;

public class XiaomiActorGatewayHandler extends XiaomiDeviceBaseHandler {

    public XiaomiActorGatewayHandler(Thing thing) {
        super(thing);
    }

    @Override
    void execute(ChannelUID channelUID, Command command) {
        switch (channelUID.getId()) {
            case CHANNEL_BRIGHTNESS:
                if (command instanceof PercentType) {
                    writeBridgeLightColor(getGatewayLightColor(), ((PercentType) command).floatValue() / 100);
                } else if (command instanceof OnOffType) {
                    writeBridgeLightColor(getGatewayLightColor(), command == OnOffType.ON ? 1 : 0);
                } else {
                    logger.error("Can't handle command {} on channel {}", command, channelUID);
                }
                break;
            case CHANNEL_COLOR:
                if (command instanceof HSBType) {
                    writeBridgeLightColor(((HSBType) command).getRGB() & 0xffffff, getGatewayLightBrightness());
                }
                break;
            case CHANNEL_COLOR_TEMPERATURE:
                if (command instanceof PercentType) {
                    PercentType colorTemperature = (PercentType) command;
                    int kelvin = 48 * colorTemperature.intValue() + 1700;
                    int color = ColorUtil.getRGBFromK(kelvin);
                    writeBridgeLightColor(color, getGatewayLightBrightness());
                    updateState(CHANNEL_COLOR,
                            HSBType.fromRGB((color / 256 / 256) & 0xff, (color / 256) & 0xff, color & 0xff));
                } else {
                    logger.error("Can't handle command {} on channel {}", command, channelUID);
                }
                break;
            case CHANNEL_GATEWAY_SOUND:
                if (command instanceof DecimalType) {
                    State state = getItemInChannel(CHANNEL_GATEWAY_VOLUME).getState();
                    // get volume, default is 50%
                    int volume = (state instanceof DecimalType && state != null) ? ((DecimalType) state).intValue()
                            : 50;
                    writeBridgeRingtone(((DecimalType) command).intValue(), volume);
                    updateState(CHANNEL_GATEWAY_SOUND_SWITCH, OnOffType.ON);
                } else {
                    logger.error("Can't handle command {} on channel {}", command, channelUID);
                }
                break;
            case CHANNEL_GATEWAY_SOUND_SWITCH:
                if (command instanceof OnOffType) {
                    if (((OnOffType) command) == OnOffType.OFF) {
                        stopRingtone();
                    }
                } else {
                    logger.error("Can't handle command {} on channel {}", command, channelUID);
                }
                break;
            case CHANNEL_GATEWAY_VOLUME:
                // nothing to do, just suppress error
                break;
            default:
                logger.error("Can't handle command {} on channel {}", command, channelUID);
                break;
        }
    }

    @Override
    void parseReport(JsonObject data) {
        if (data.has("rgb")) {
            long rgb = data.get("rgb").getAsLong();
            updateState(CHANNEL_BRIGHTNESS, new PercentType((int) (((rgb >> 32) & 0xff) / 2.55)));
            updateState(CHANNEL_COLOR,
                    HSBType.fromRGB((int) (rgb >> 16) & 0xff, (int) (rgb >> 8) & 0xff, (int) rgb & 0xff));
        }
    }

    private int getGatewayLightColor() {
        Item item = getItemInChannel(CHANNEL_COLOR);
        if (item == null) {
            return 0xffffff;
        }

        State state = item.getState();
        if (state != null && state instanceof HSBType) {
            return ((HSBType) state).getRGB() & 0xffffff;
        }

        return 0xffffff;
    }

    private float getGatewayLightBrightness() {
        Item item = getItemInChannel(CHANNEL_BRIGHTNESS);
        if (item == null) {
            return 1f;
        }

        State state = item.getState();
        if (state == null) {
            return 1f;
        } else if (state instanceof PercentType) {
            PercentType brightness = (PercentType) state;
            return brightness.floatValue() / 100;
        } else if (state instanceof OnOffType) {
            return state == OnOffType.ON ? 1f : 0f;
        }

        return 1f;
    }

    private void writeBridgeLightColor(int color, float brightness) {
        long brightnessInt = ((long) (brightness * 255)) * 256 * 256 * 256;
        writeBridgeLightColor((color & 0xffffff) | (brightnessInt & 0xff000000));
    }

    private void writeBridgeLightColor(long color) {
        getXiaomiBridgeHandler().writeToBridge(new String[] { "rgb" }, new Object[] { color });
    }

    /**
     * Play ringtone on Xiaomi Gateway
     * 0 - 8, 10 - 13, 20 - 29 -- ringtones that come with the system)
     * > 10001 -- user-defined ringtones
     *
     * @param ringtoneId
     */
    private void writeBridgeRingtone(int ringtoneId, int volume) {
        getXiaomiBridgeHandler().writeToBridge(new String[] { "mid", "vol" }, new Object[] { ringtoneId, volume });
    }

    /**
     * Stop playing ringtone on Xiaomi Gateway
     * by setting "mid" parameter to 10000
     */
    private void stopRingtone() {
        getXiaomiBridgeHandler().writeToBridge(new String[] { "mid" }, new Object[] { 10000 });
    }

}

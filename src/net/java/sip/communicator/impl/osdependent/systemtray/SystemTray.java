/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.java.sip.communicator.impl.osdependent.systemtray;

import java.awt.*;

import javax.swing.*;

import org.jitsi.util.*;

import net.java.sip.communicator.impl.osdependent.*;
import net.java.sip.communicator.impl.osdependent.systemtray.appindicator.*;
import net.java.sip.communicator.impl.osdependent.systemtray.awt.*;
import net.java.sip.communicator.util.Logger;

/**
 * Base class for all wrappers of <tt>SystemTray</tt> implementations.
 */
public abstract class SystemTray
{
    private static final String PNMAE_DISABLE_TRY =
        "net.java.sip.communicator.osdependent.systemtray.DISABLE";

    private static final Logger logger = Logger.getLogger(SystemTray.class);
    private static SystemTray systemTray;

    /**
     * Gets or creates the supported <tt>SystemTray</tt> implementations.
     * @return a <tt>SystemTray</tt> implementation for the current platform.
     */
    public final static SystemTray getSystemTray()
    {
        boolean disable = OsDependentActivator.getConfigurationService()
            .getBoolean(PNMAE_DISABLE_TRY, false);
        if (disable || GraphicsEnvironment.isHeadless())
        {
            return null;
        }

        if (systemTray == null)
        {
            if (OSUtils.IS_LINUX)
            {
                try
                {
                    systemTray = new AppIndicatorTray();
                    return systemTray;
                }
                catch(Exception ex)
                {
                    logger.info(ex.getMessage());
                }
            }

            if (java.awt.SystemTray.isSupported())
            {
                systemTray = new AWTSystemTray();
            }
        }

        return systemTray;
    }

    /**
     * Adds a <tt>TrayIcon</tt> to this system tray implementation.
     * 
     * @param trayIcon the <tt>TrayIcon</tt> to add
     */
    public abstract void addTrayIcon(TrayIcon trayIcon);

    /**
     * Creates an implementation specific <tt>TrayIcon</tt> that can later be
     * added with {@link #addTrayIcon(TrayIcon)}.
     * 
     * @param image the <tt>Image</tt> to be used
     * @param tooltip the string to be used as tooltip text; if the value is
     *            <tt>null</tt> no tooltip is shown
     * @param popup the menu to be used for the tray icon's popup menu; if the
     *            value is <tt>null</tt> no popup menu is shown
     * @return a <tt>TrayIcon</tt> instance for this <tt>SystemTray</tt>
     *         implementation.
     */
    public abstract TrayIcon createTrayIcon(ImageIcon icon, String tooltip,
        Object popup);

    /**
     * Determines if the popup menu for the icon is to be a Swing
     * <tt>JPopupMenu</tt> or an AWT <tt>PopupMenu</tt>
     * 
     * @return <tt>true</tt> for a <tt>JPopupMenu</tt>, <tt>false</tt> for a
     *         <tt>PopupMenu</tt>
     */
    public abstract boolean useSwingPopupMenu();

    /**
     * Determines if the tray icon supports dynamic menus.
     * 
     * @return True if the menu can be changed while running, false otherwise.
     */
    public abstract boolean supportsDynamicMenu();
}

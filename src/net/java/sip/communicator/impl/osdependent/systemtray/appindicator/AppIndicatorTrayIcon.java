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
package net.java.sip.communicator.impl.osdependent.systemtray.appindicator;

import java.awt.*;
import java.awt.TrayIcon.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.beans.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.Timer;

import javax.accessibility.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import javax.print.attribute.standard.*;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import org.jitsi.util.*;
import org.jitsi.util.Logger;

import com.sun.jna.*;

import net.java.sip.communicator.impl.osdependent.*;
import net.java.sip.communicator.impl.osdependent.systemtray.*;
import net.java.sip.communicator.impl.osdependent.systemtray.TrayIcon;
import net.java.sip.communicator.impl.osdependent.systemtray.appindicator.Gobject.*;
import net.java.sip.communicator.util.*;

/**
 * System tray icon implementation based on libappindicator1.
 * 
 * @author Ingo Bauersachs
 */
class AppIndicatorTrayIcon implements TrayIcon
{
    private static final Logger logger =
        Logger.getLogger(AppIndicatorTrayIcon.class);
    
    // shortcuts
    private static Gobject gobject = Gobject.INSTANCE;
    private static Gtk gtk = Gtk.INSTANCE;
    private static AppIndicator1 ai = AppIndicator1.INSTANCE;

    // references to the root menu and the native icon
    private ImageIcon mainIcon;
    private String title;
    private JPopupMenu popup;
    private Map<String, String> extractedFiles = new HashMap<>();
    private PopupMenuPeer popupPeer;
    private AppIndicator1.AppIndicator appIndicator;

    public AppIndicatorTrayIcon(ImageIcon mainIcon, String title,
        JPopupMenu popup)
    {
        this.mainIcon = mainIcon;
        this.title = title;
        this.popup = popup;
        this.popupPeer = null;
    }

    /**
     * Combines the references of each swing menu item with the GTK counterpart
     */
    private class PopupMenuPeer implements ContainerListener
    {
        public PopupMenuPeer(PopupMenuPeer parent, Component em)
        {
            menuItem = em;

            // if this menu item is a submenu, add ourselves as listener to
            // add or remove the native counterpart
            if (em instanceof JMenu)
            {
                ((JMenu)em).getPopupMenu().addContainerListener(this);
                ((JMenu)em).addContainerListener(this);
            }
        }

        public List<PopupMenuPeer> children = new ArrayList<>();
        public Pointer gtkMenuItem;
        public Pointer gtkMenu;
        public Pointer gtkImage;
        public Memory gtkImageBuffer;
        public Pointer gtkPixbuf;
        public Component menuItem;
        public MenuItemSignalHandler signalHandler;

        @Override
        public void componentAdded(ContainerEvent e)
        {
            AppIndicatorTrayIcon.this.printMenu(popup.getComponents(), 1);
            gtk.gdk_threads_enter();
            createGtkMenuItems(this, new Component[]{e.getChild()});
            gtk.gtk_widget_show_all(popupPeer.gtkMenu);
            gtk.gdk_threads_leave();
        }

        @Override
        public void componentRemoved(ContainerEvent e)
        {
            AppIndicatorTrayIcon.this.printMenu(popup.getComponents(), 1);
            for (PopupMenuPeer c : children)
            {
                if (c.menuItem == e.getChild())
                {
                    gtk.gdk_threads_enter();
                    gtk.gtk_widget_destroy(c.gtkMenuItem);
                    gtk.gdk_threads_leave();
                    cleanMenu(c);
                    break;
                }
            }
        }
    }

    public void createTray()
    {
        appIndicator = ai.app_indicator_new(
            "jitsi",
            "indicator-messages-new",
            AppIndicator1.APP_INDICATOR_CATEGORY.COMMUNICATIONS.ordinal());

        ai.app_indicator_set_title(appIndicator, title);
        setupGtkMenu();

        String path = imageIconToPath(mainIcon);
        if (path != null)
        {
            ai.app_indicator_set_icon_full(appIndicator, path, "Jitsi");
        }

        ai.app_indicator_set_status(
            appIndicator,
            AppIndicator1.APP_INDICATOR_STATUS.ACTIVE.ordinal());

        new Thread()
        {
            public void run()
            {
                gtk.gtk_main();
            }
        }.start();
    }

    private void setupGtkMenu()
    {
        // create root menu
        popupPeer = new PopupMenuPeer(null, popup);
        popupPeer.gtkMenu = gtk.gtk_menu_new();

        // transfer everything in the swing menu to the gtk menu
        createGtkMenuItems(popupPeer, popup.getComponents());
        gtk.gtk_widget_show_all(popupPeer.gtkMenu);

        // attach the menu to the indicator
        ai.app_indicator_set_menu(appIndicator, popupPeer.gtkMenu);
    }

    private void cleanMenu(PopupMenuPeer peer)
    {
        for (PopupMenuPeer p : peer.children)
        {
            cleanMenu(p);
        }

        // - the root menu is released when it's unset from the indicator
        // - gtk auto-frees menu item, submenu, image, and pixbuf
        // - the imagebuffer was jna allocated, GC should take care of freeing
        peer.gtkImageBuffer = null;
        removeListeners(peer);
    }

    private void removeListeners(PopupMenuPeer peer)
    {
        if (peer.menuItem instanceof JMenu)
        {
            ((JMenu)peer.menuItem).removeContainerListener(peer);
            ((JMenu)peer.menuItem).getPopupMenu().removeContainerListener(peer);
        }

        for (PopupMenuPeer p : peer.children)
        {
            removeListeners(p);
        }
    }

    private void createGtkMenuItems(
        PopupMenuPeer parent,
        Component[] components)
    {
        for (Component em : components)
        {
            logger.debug("Creating item for " + em.getClass().getName());
            PopupMenuPeer peer = new PopupMenuPeer(parent, em);
            if (em instanceof JPopupMenu.Separator)
            {
                peer.gtkMenuItem = gtk.gtk_separator_menu_item_new();
            }

            if (em instanceof JMenuItem)
            {
                JMenuItem m = (JMenuItem)em;
                logger.debug(" title: " + m.getText());
                createGtkMenuItem(peer);
            }

            if (em instanceof JMenu && peer.gtkMenuItem != null)
            {
                JMenu m = (JMenu)em;
                logger.debug("Creating submenu on " + m.getText());
                peer.gtkMenu = gtk.gtk_menu_new();
                createGtkMenuItems(peer, m.getMenuComponents());
                gtk.gtk_menu_item_set_submenu(peer.gtkMenuItem, peer.gtkMenu);
            }

            if (peer.gtkMenuItem != null)
            {
                parent.children.add(peer);
                gtk.gtk_menu_shell_append(parent.gtkMenu, peer.gtkMenuItem);
            }
        }
    }

    private void createGtkMenuItem(PopupMenuPeer peer)
    {
        JMenuItem m = (JMenuItem)peer.menuItem;
        if (m instanceof JCheckBoxMenuItem)
        {
            peer.gtkMenuItem = gtk.gtk_check_menu_item_new_with_label(
                m.getText());
            JCheckBoxMenuItem cb = (JCheckBoxMenuItem)m;
            gtk.gtk_check_menu_item_set_active(
                peer.gtkMenuItem,
                cb.isSelected() ? 1 : 0);
        }
        else
        {
            peer.gtkMenuItem = gtk.gtk_image_menu_item_new_with_label(
                m.getText());
            if (m.getIcon() instanceof ImageIcon)
            {
                ImageIcon ii = ((ImageIcon) m.getIcon());
                imageIconToGtkWidget(peer, ii);
                if (peer.gtkImage != null)
                {
                    gtk.gtk_image_menu_item_set_image(
                        peer.gtkMenuItem,
                        peer.gtkImage);
                    gtk.gtk_image_menu_item_set_always_show_image(
                        peer.gtkMenuItem,
                        1);
                }
            }
        }

        if (peer.gtkMenuItem == null)
        {
            logger.debug("Could not create menu item for " + m.getText());
            return;
        }

        MenuItemChangeListener micl = new MenuItemChangeListener(peer);
        m.addPropertyChangeListener(micl);
        m.addChangeListener(micl);

        // skip GTK events if it's a submenu
        if (!(m instanceof JMenu))
        {
            gtk.gtk_widget_set_sensitive(
                peer.gtkMenuItem,
                m.isEnabled() ? 1 : 0);
            peer.signalHandler = new MenuItemSignalHandler(peer);
            gobject.g_signal_connect_data(
                peer.gtkMenuItem,
                "activate",
                peer.signalHandler,
                null,
                null,
                0);
        }
    }

    private String imageIconToPath(ImageIcon ii)
    {
        if (ii.getDescription() != null)
        {
            String path = extractedFiles.get(ii.getDescription());
            if (path != null)
            {
                return path;
            }
        }

        try
        {
            File f = File.createTempFile("jitsi-appindicator", ".png");
            f.deleteOnExit();
            try (FileImageOutputStream fios = new FileImageOutputStream(f))
            {
                if (!ImageIO.write(getBufferedImage(ii), "png", fios))
                {
                    return null;
                }

                if (ii.getDescription() != null)
                {
                    extractedFiles.put(
                        ii.getDescription(),
                        f.getAbsolutePath());
                }

                return f.getAbsolutePath();
            }
        }
        catch (IOException e)
        {
            logger.debug("Failed to extract image: " + ii.getDescription(), e);
        }

        return null;
    }

    BufferedImage getBufferedImage(ImageIcon ii)
    {
        Image img = ii.getImage();
        if (img == null)
        {
            return null;
        }

        if (img instanceof BufferedImage)
        {
            return (BufferedImage) img;
        }

        BufferedImage bi = new BufferedImage(
            img.getWidth(null),
            img.getHeight(null),
            BufferedImage.TYPE_INT_ARGB);
        Graphics g = bi.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return bi;
    }

    private void imageIconToGtkWidget(PopupMenuPeer peer, ImageIcon ii)
    {
        BufferedImage bi = getBufferedImage(ii);
        if (bi == null)
        {
            return;
        }

        int[] pixels = bi.getRGB(
            0,
            0,
            bi.getWidth(),
            bi.getHeight(),
            null,
            0,
            bi.getWidth());

        peer.gtkImageBuffer = new Memory(pixels.length * 4);
        for (int i = 0; i < pixels.length; i++)
        {
            // convert from argb (big endian) -> rgba (little endian) => abgr
            peer.gtkImageBuffer.setInt(i * 4, (pixels[i] & 0xFF000000) |
                (pixels[i] << 16) |
                (pixels[i] & 0xFF00) |
                (pixels[i] >>> 16 & 0xFF));
        }

        peer.gtkPixbuf = gtk.gdk_pixbuf_new_from_data(
            peer.gtkImageBuffer,
            0,
            1,
            8,
            bi.getWidth(),
            bi.getHeight(),
            bi.getWidth() * 4,
            null,
            null);
        peer.gtkImage = gtk.gtk_image_new_from_pixbuf(peer.gtkPixbuf);

        // Now that the image ref's the buffer, we can release our own ref and
        // the buffer will be free'd along with the image 
        gobject.g_object_unref(peer.gtkPixbuf);
    }

    private static class MenuItemChangeListener
        implements PropertyChangeListener, ChangeListener
    {
        private PopupMenuPeer peer;
        private JMenuItem menu;

        public MenuItemChangeListener(PopupMenuPeer peer)
        {
            this.peer = peer;
            this.menu = (JMenuItem)peer.menuItem;
        }

        @Override
        public void propertyChange(PropertyChangeEvent evt)
        {
            if (logger.isDebugEnabled())
            {
                logger.debug(menu.getText() + "::" + evt);
            }

            switch (evt.getPropertyName())
            {
            case JMenuItem.TEXT_CHANGED_PROPERTY:
                gtk.gdk_threads_enter();
                gtk.gtk_menu_item_set_label(
                    peer.gtkMenuItem,
                    evt.getNewValue().toString());
                gtk.gdk_threads_leave();
                break;
//            case JMenuItem.ICON_CHANGED_PROPERTY:
//                gtk.gtk_image_menu_item_set_image(gtkMenuItem, image);
//                break;
            case AccessibleContext.ACCESSIBLE_STATE_PROPERTY:
                gtk.gtk_widget_set_sensitive(
                    peer.gtkMenuItem,
                    AccessibleState.ENABLED.equals(evt.getNewValue()) ? 1 : 0);
                break;
            }
        }

        @Override
        public void stateChanged(ChangeEvent e)
        {
            logger.debug(menu.getText() + " -> " + menu.isSelected());
            gtk.gdk_threads_enter();
            gtk.gtk_check_menu_item_set_active(
                peer.gtkMenuItem,
                menu.isSelected() ? 1 : 0);
            gtk.gdk_threads_leave();
        }
    }

    private static class MenuItemSignalHandler
        implements SignalHandler, Runnable
    {
        private PopupMenuPeer peer;

        MenuItemSignalHandler(PopupMenuPeer peer)
        {
            this.peer = peer;
        }

        @Override
        public void signal(Pointer widget, Pointer data)
        {
            SwingUtilities.invokeLater(this);
        }

        @Override
        public void run()
        {
            JMenuItem menu = (JMenuItem)peer.menuItem;
            if (menu instanceof JCheckBoxMenuItem)
            {
                // Ignore GTK callback events if the menu state is
                // already the same. Setting the selected state on the
                // GTK sends the "activate" event, and would cause
                // a loop
                if (menu.isSelected() == isGtkSelected())
                {
                    return;
                }
            }

            for (ActionListener l : menu.getActionListeners())
            {
                logger.debug("Invoking " + l + " on " + menu.getText());
                l.actionPerformed(new ActionEvent(menu, 0, "activate"));
            }
        }

        private boolean isGtkSelected()
        {
            return (gtk.gtk_check_menu_item_get_active(peer.gtkMenuItem) == 1);
        }
    }

    @Override
    public void setDefaultAction(Object menuItem)
    {
        Pointer gtkMenuItem = findMenuItem(popupPeer, menuItem);
        if (gtkMenuItem != null)
        {
            ai.app_indicator_set_secondary_activate_target(
                appIndicator,
                gtkMenuItem);
        }
    }

    private Pointer findMenuItem(PopupMenuPeer peer, Object menuItem)
    {
        if (peer.menuItem == menuItem)
        {
            return peer.gtkMenuItem;
        }

        for (PopupMenuPeer p : peer.children)
        {
            Pointer found = findMenuItem(p, menuItem);
            if (found != null)
            {
                return found;
            }
        }

        return null;
    }

    @Override
    public void addBalloonActionListener(ActionListener listener)
    {
        // not supported
    }

    @Override
    public void displayMessage(String caption, String text,
        MessageType messageType)
    {
        // not supported
    }

    @Override
    public void setIcon(ImageIcon icon) throws NullPointerException
    {
        ai.app_indicator_set_icon_full(
            appIndicator,
            imageIconToPath(icon),
            "Jitsi");
    }

    @Override
    public void setIconAutoSize(boolean autoSize)
    {
        // nothing to do
    }

    private void printMenu(Component[] components, int indent)
    {
        if (!logger.isDebugEnabled())
        {
            return;
        }

        String p = String.format("%0" + indent * 4 + "d", 0).replace('0', ' ');
        for (Component em : components)
        {
            if (em instanceof JPopupMenu.Separator)
            {
                logger.debug(p + "-----------------------");
            }

            if (em instanceof JMenuItem)
            {
                JMenuItem m = (JMenuItem) em;
                logger.debug(p + em.getClass().getName() + ": " + m.getText());
            }

            if (em instanceof JMenu)
            {
                JMenu m = (JMenu) em;
                printMenu(m.getMenuComponents(), indent + 1);
            }
        }
    }
};
package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import net.i2p.I2PAppContext;
import net.i2p.app.*;
import static net.i2p.app.ClientAppState.*;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.data.DataHelper;
import net.i2p.util.FileUtil;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SystemVersion;

/**
 * Coordinate a set of tunnels within the JVM, loading and storing their config
 * to disk, and building new ones as requested.
 *
 * This is the entry point from clients.config.
 */
public class TunnelControllerGroup implements ClientApp {
    private final Log _log;
    private volatile ClientAppState _state;
    private final I2PAppContext _context;
    private final ClientAppManager _mgr;
    private static volatile TunnelControllerGroup _instance;
    static final String DEFAULT_CONFIG_FILE = "i2ptunnel.config";
    private static final String CONFIG_DIR = "i2ptunnel.config.d";
    private static final String PREFIX = "tunnel.";

    private final List<TunnelController> _controllers;
    private final ReadWriteLock _controllersLock;
    // locking: this
    private boolean _controllersLoaded;
    private final String _configFile;
    private final String _configDirectory;

    private static final String REGISTERED_NAME = "i2ptunnel";

    /**
     * Map of I2PSession to a Set of TunnelController objects
     * using the session (to prevent closing the session until
     * no more tunnels are using it)
     *
     */
    private final Map<I2PSession, Set<TunnelController>> _sessions;

    /**
     *  We keep a pool of socket handlers for all clients,
     *  as there is no need for isolation on the client side.
     *  Extending classes may use it for other purposes.
     *
     *  May also be used by servers, carefully,
     *  as there is no limit on threads.
     */
    private ThreadPoolExecutor _executor;
    private static final AtomicLong _executorThreadCount = new AtomicLong();
    private final Object _executorLock = new Object();
    /** how long to wait before dropping an idle thread */
    private static final long HANDLER_KEEPALIVE_MS = 2*60*1000;


    /**
     *  In I2PAppContext will instantiate if necessary and always return non-null.
     *  As of 0.9.4, when in RouterContext, will return null (except in Android)
     *  if the TCG has not yet been started by the router.
     *
     *  @throws IllegalArgumentException if unable to load from i2ptunnel.config
     */
    public static TunnelControllerGroup getInstance() {
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null) {
                I2PAppContext ctx = I2PAppContext.getGlobalContext();
                if (SystemVersion.isAndroid() || !ctx.isRouterContext()) {
                    _instance = new TunnelControllerGroup(ctx, null, null);
                    if (!SystemVersion.isAndroid())
                        _instance.startup();
                } // else wait for the router to start it
            }
            return _instance;
        }
    }

    /**
     *  Instantiation only. Caller must call startup().
     *  Config file problems will not throw exception until startup().
     *
     *  @param mgr may be null
     *  @param args one arg, the config file, if not absolute will be relative to the context's config dir,
     *              if empty or null, the default is i2ptunnel.config
     *  @throws IllegalArgumentException if too many args
     *  @since 0.9.4
     */
    public TunnelControllerGroup(I2PAppContext context, ClientAppManager mgr, String[] args) {
        _state = UNINITIALIZED;
        _context = context;
        _mgr = mgr;
        _log = _context.logManager().getLog(TunnelControllerGroup.class);
        _controllers = new ArrayList<TunnelController>();
        _controllersLock = new ReentrantReadWriteLock(true);
        if (args == null || args.length <= 0){
            _configFile = DEFAULT_CONFIG_FILE;
            _configDirectory = CONFIG_DIR;
        }else if (args.length == 1){
            File check = new File(args[0]);
            if (check.isFile()) {
                _configFile = args[0];
                _configDirectory = CONFIG_DIR;
            }else{
                _configFile = DEFAULT_CONFIG_FILE;
                _configDirectory = args[0];
            }
        }else if (args.length == 2){
            _configFile = args[0];
            _configDirectory = args[1];
        }else{
            throw new IllegalArgumentException("Usage: TunnelControllerGroup [filename] [configdirectory] ");
        }
        _sessions = new HashMap<I2PSession, Set<TunnelController>>(4);
        synchronized (TunnelControllerGroup.class) {
            if (_instance == null)
                _instance = this;
        }
        if (_instance != this) {
            _log.logAlways(Log.WARN, "New TunnelControllerGroup, now you have two");
            if (_log.shouldLog(Log.WARN))
                _log.warn("I did it", new Exception());
        }
        _state = INITIALIZED;
    }

    /**
     *  @param args one arg, the config file, if not absolute will be relative to the context's config dir,
     *              if no args, the default is i2ptunnel.config
     *  @throws IllegalArgumentException if unable to load from config from file
     */
    public static void main(String args[]) {
        synchronized (TunnelControllerGroup.class) {
            if (_instance != null) return; // already loaded through the web
            _instance = new TunnelControllerGroup(I2PAppContext.getGlobalContext(), null, args);
            _instance.startup();
        }
    }

    /**
     *  ClientApp interface
     *  @throws IllegalArgumentException if unable to load config from file
     *  @since 0.9.4
     */
    public void startup() {
        List<String> fileList = configFiles();
        for (int i = 0; i < fileList.size(); i++) {
            String configFile = fileList.get(i);
            try {
                loadControllers(configFile);
            } catch (IllegalArgumentException iae) {
                if (DEFAULT_CONFIG_FILE.equals(configFile) && !_context.isRouterContext()) {
                    // for i2ptunnel command line
                    synchronized (this) {
                        _controllersLoaded = true;
                    }
                    _log.logAlways(Log.WARN, "Not in router context and no preconfigured tunnels");
                } else {
                    throw iae;
                }
            }
            startControllers();
            if (_mgr != null)
                _mgr.register(this);
                // RouterAppManager registers its own shutdown hook
            else
                _context.addShutdownTask(new Shutdown());
        }
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public ClientAppState getState() {
        return _state;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public String getName() {
        return REGISTERED_NAME;
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public String getDisplayName() {
        return REGISTERED_NAME;
    }

    /**
     *  @since 0.9.4
     */
    private void changeState(ClientAppState state) {
        changeState(state, null);
    }

    /**
     *  @since 0.9.4
     */
    private synchronized void changeState(ClientAppState state, Exception e) {
        _state = state;
        if (_mgr != null)
            _mgr.notify(this, state, null, e);
    }

    /**
     *  Warning - destroys the singleton!
     *  @since 0.8.8
     */
    private class Shutdown implements Runnable {
        public void run() {
            shutdown();
        }
    }

    /**
     *  ClientApp interface
     *  @since 0.9.4
     */
    public void shutdown(String[] args) {
        shutdown();
    }

    /**
     *  Warning - destroys the singleton!
     *  Caller must root a new context before calling instance() or main() again.
     *  Agressively kill and null everything to reduce memory usage in the JVM
     *  after stopping, and to recognize what must be reinitialized on restart (Android)
     *
     *  @since 0.8.8
     */
    public synchronized void shutdown() {
        if (_state != STARTING && _state != RUNNING)
            return;
        changeState(STOPPING);
        if (_mgr != null)
            _mgr.unregister(this);
        unloadControllers();
        synchronized (TunnelControllerGroup.class) {
            if (_instance == this)
                _instance = null;
        }
        killClientExecutor();
        changeState(STOPPED);
    }

    /**
     * Load up all of the tunnels configured in the given file.
     * Prior to 0.9.20, also started the tunnels.
     * As of 0.9.20, does not start the tunnels, you must call startup()
     * or getInstance() instead of loadControllers().
     *
     * DEPRECATED for use outside this class. Use startup() or getInstance().
     *
     * @throws IllegalArgumentException if unable to load from file
     */
    public synchronized void loadControllers(String configFile) {
        if (_controllersLoaded)
            return;
        boolean shouldMigrate = _context.isRouterContext() && !SystemVersion.isAndroid();
        loadControllers(configFile, shouldMigrate);
    }

    /**
     * @param shouldMigrate migrate to, and load from, i2ptunnel.config.d
     * @since 0.9.34
     * @throws IllegalArgumentException if unable to load from file
     */
    private synchronized void loadControllers(String configFile, boolean shouldMigrate) {
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(_context.getConfigDir(), configFile);
        File dir = new SecureDirectory(cfgFile.getParent(), CONFIG_DIR);
        List<Properties> props = null;
        if (cfgFile.exists()) {
            try {
                List<Properties> cfgs = loadConfig(cfgFile);
                if (shouldMigrate) {
                    boolean ok = migrate(cfgs, cfgFile, dir);
                    if (!ok)
                        shouldMigrate = false;
                }
            } catch (IOException ioe) {
                _log.error("Unable to load the controllers from " + cfgFile.getAbsolutePath());
                throw new IllegalArgumentException("Unable to load the controllers from " + cfgFile, ioe);
            }
        } else if (!shouldMigrate) {
                throw new IllegalArgumentException("Unable to load the controllers from " + cfgFile);
        }
        int i = 0;
        _controllersLock.writeLock().lock();
        try {
            if (shouldMigrate && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null && files.length > 0) {
                    // sort so the returned order is consistent
                    Arrays.sort(files);
                    for (File f : files) {
                        if (!f.getName().endsWith(".config"))
                            continue;
                        if (!f.isFile())
                            continue;
                        try {
                            props = loadConfig(f);
                            if (!props.isEmpty()) {
                                for (Properties cfg : props) {
                                    String type = cfg.getProperty("type");
                                    if (type == null)
                                        continue;
                                    TunnelController controller = new TunnelController(cfg, "");
                                    _controllers.add(controller);
                                    i++;
                                }
                            } else {
                                _log.error("Error loading the client app properties from " + f);
                                System.out.println("Error loading the client app properties from " + f);
                            }
                        } catch (IOException ioe) {
                            _log.error("Error loading the client app properties from " + f, ioe);
                            System.out.println("Error loading the client app properties from " + f + ' ' + ioe);
                        }
                    }
                }
            } else {
                // use what we got from i2ptunnel.config
                for (Properties cfg : props) {
                    String type = cfg.getProperty("type");
                    if (type == null)
                        continue;
                    TunnelController controller = new TunnelController(cfg, "");
                    _controllers.add(controller);
                    i++;
                }
            }
        } finally {
            _controllersLock.writeLock().unlock();
        }

        _controllersLoaded = true;
        if (i > 0) {
            _controllersLoaded = true;
            if (_log.shouldLog(Log.INFO))
                _log.info(i + " controllers loaded from " + configFile);
        } else {
            _log.logAlways(Log.WARN, "No i2ptunnel configurations found in " + cfgFile + " or " + dir);
        }
    }

    /*
     * Migrate tunnels from file to individual files in dir
     *
     * @return success
     * @since 0.9.34
     */
    private boolean migrate(List<Properties> tunnels, File from, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs())
            return false;
        boolean ok = true;
        for (int i = 0; i < tunnels.size(); i++) {
            Properties props = tunnels.get(i);
            String tname = props.getProperty("name");
            if (tname == null)
                tname = "tunnel";
            String name = i + "-" + tname + "-i2ptunnel.config";
            if (i < 10)
                name = '0' + name;
            File f = new File(dir, name);
            props.setProperty("configFile", f.getAbsolutePath());
            Properties save = new OrderedProperties();
            for (Map.Entry<Object, Object> e : props.entrySet()) {
                String key = (String) e.getKey();
                String val = (String) e.getValue();
                save.setProperty(PREFIX + i + '.' + key, val);
            }
            try {
                DataHelper.storeProps(save, f);
            } catch (IOException ioe) {
                _log.error("Error migrating the i2ptunnel configuration to " + f, ioe);
                System.out.println("Error migrating the i2ptunnel configuration to " + f + ' ' + ioe);
                ok = false;
            }
        }
        if (ok) {
            if (!FileUtil.rename(from, new File(from.getAbsolutePath() + ".bak")))
                from.delete();
        }
        return ok;
    }

    /**
     * Start all of the tunnels. Must call loadControllers() first.
     * @since 0.9.20
     */
    private synchronized void startControllers() {
        changeState(STARTING);
        I2PAppThread startupThread = new I2PAppThread(new StartControllers(), "Startup tunnels");
        startupThread.start();
        changeState(RUNNING);
    }

    private class StartControllers implements Runnable {
        public void run() {
            synchronized(TunnelControllerGroup.this) {
                _controllersLock.readLock().lock();
                try {
                    if (_controllers.size() <= 0) {
                        _log.logAlways(Log.WARN, "No configured tunnels to start");
                        return;
                    }
                    for (int i = 0; i < _controllers.size(); i++) {
                        TunnelController controller = _controllers.get(i);
                        if (controller.getStartOnLoad())
                            controller.startTunnelBackground();
                    }
                } finally {
                    _controllersLock.readLock().unlock();
                }
            }
        }
    }

    /**
     * Stop all tunnels, reload config, and restart those configured to do so.
     * WARNING - Does NOT simply reload the configuration!!! This is probably not what you want.
     * This does not return or clear the controller messages.
     *
     * @throws IllegalArgumentException if unable to reload config file
     */
    public synchronized void reloadControllers() {
        List<String> fileList = configFiles();
        for (int i = 0; i < fileList.size(); i++) {
            String configFile = fileList.get(i);
            unloadControllers();
            loadControllers(configFile);
            startControllers();
        }
    }

    /**
     * Stop and remove reference to all known tunnels (but dont delete any config
     * file or do other silly things)
     *
     */
    public synchronized void unloadControllers() {
        if (!_controllersLoaded)
            return;

        _controllersLock.writeLock().lock();
        try {
            destroyAllControllers();
            _controllers.clear();
        } finally {
            _controllersLock.writeLock().unlock();
        }

        _controllersLoaded = false;
        if (_log.shouldLog(Log.INFO))
            _log.info("All controllers stopped and unloaded");
    }

    /**
     * Add the given tunnel to the set of known controllers (but dont add it to
     * a config file or start it or anything)
     *
     */
    public synchronized void addController(TunnelController controller) {
        _controllersLock.writeLock().lock();
        try {
            _controllers.add(controller);
        } finally {
            _controllersLock.writeLock().unlock();
        }
    }

    /**
     * Stop and remove the given tunnel.
     * Side effect - clears all messages the controller.
     * Does NOT delete the configuration - must call saveConfig() or removeConfig() also.
     *
     * @return list of messages from the controller as it is stopped
     */
    public synchronized List<String> removeController(TunnelController controller) {
        if (controller == null) return new ArrayList<String>();
        controller.stopTunnel();
        List<String> msgs = controller.clearMessages();
        _controllersLock.writeLock().lock();
        try {
            _controllers.remove(controller);
        } finally {
            _controllersLock.writeLock().unlock();
        }
        msgs.add("Tunnel " + controller.getName() + " removed");
        return msgs;
    }

    /**
     * Stop all tunnels. May be restarted.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when stopped
     */
    public synchronized List<String> stopAllControllers() {
        List<String> msgs = new ArrayList<String>();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                controller.stopTunnel();
                msgs.addAll(controller.clearMessages());
            }
            if (_log.shouldLog(Log.INFO))
                _log.info(_controllers.size() + " controllers stopped");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     *  Stop all tunnels. They may not be restarted, you must reload.
     *  Caller must synch. Caller must clear controller list.
     *
     *  @since 0.9.17
     */
    private void destroyAllControllers() {
        for (int i = 0; i < _controllers.size(); i++) {
            TunnelController controller = _controllers.get(i);
            controller.destroyTunnel();
        }
        if (_log.shouldLog(Log.INFO))
            _log.info(_controllers.size() + " controllers stopped");
    }

    /**
     * Start all tunnels.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when started
     */
    public synchronized List<String> startAllControllers() {
        List<String> msgs = new ArrayList<String>();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                controller.startTunnelBackground();
                msgs.addAll(controller.clearMessages());
            }

            if (_log.shouldLog(Log.INFO))
                _log.info(_controllers.size() + " controllers started");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Restart all tunnels.
     * Side effect - clears all messages from all controllers.
     *
     * @return list of messages the tunnels generate when restarted
     */
    public synchronized List<String> restartAllControllers() {
        List<String> msgs = new ArrayList<String>();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                controller.restartTunnel();
                msgs.addAll(controller.clearMessages());
            }
            if (_log.shouldLog(Log.INFO))
                _log.info(_controllers.size() + " controllers restarted");
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Fetch and clear all outstanding messages from any of the known tunnels.
     *
     * @return list of messages the tunnels have generated
     */
    public List<String> clearAllMessages() {
        List<String> msgs = new ArrayList<String>();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                msgs.addAll(controller.clearMessages());
            }
        } finally {
            _controllersLock.readLock().unlock();
        }
        return msgs;
    }

    /**
     * Save the configuration of all known tunnels to the default config
     * file
     *
     * @deprecated use saveConfig(TunnelController) or removeConfig(TunnelController)
     */
    @Deprecated
    public void saveConfig() throws IOException {
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                saveConfig(controller);
            }
        } finally {
            _controllersLock.readLock().unlock();
        }
    }

    /**
     * Save the configuration of all known tunnels to the given file
     * @deprecated
     *//*
    @Deprecated
    private synchronized void saveConfig(String configFile) throws IOException {
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(_context.getConfigDir(), configFile);
        File parent = cfgFile.getParentFile();
        if ( (parent != null) && (!parent.exists()) )
            parent.mkdirs();

        Properties map = new OrderedProperties();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                Properties cur = controller.getConfig(PREFIX + i + ".");
                map.putAll(cur);
            }
        } finally {
            _controllersLock.readLock().unlock();
        }

        DataHelper.storeProps(map, cfgFile);
    }*/

    /**
     * Save the configuration of this tunnel only, may be new
     * @since 0.9.34
     */
    public synchronized void saveConfig(TunnelController tc) throws IOException {
        List<String> fileList = configFiles();
        boolean done = false;
        Properties inputController = tc.getConfig("");
        String inputName = inputController.getProperty("name");
        Properties map = new OrderedProperties();
        for (int i = 0; i < fileList.size(); i++) {
            if (inConfig(tc, fileList.get(i)) != "") {
                File cfgFile = new File(fileList.get(i));
                if (!cfgFile.isAbsolute())
                    cfgFile = new File(_context.getConfigDir(), fileList.get(i));
                File parent = cfgFile.getParentFile();
                if ( (parent != null) && (!parent.exists()) )
                    parent.mkdirs();

                _controllersLock.readLock().lock();
                try {
                    for (int j = 0; j < _controllers.size(); j++) {
                        TunnelController controller = _controllers.get(j);
                        Properties controllerProperties = controller.getConfig(PREFIX + j + ".");
                        String curName = controllerProperties.getProperty("name");
                        if (curName != inputName) {
                            map.putAll(controllerProperties);
                        }
                    }
                } finally {
                    map.putAll(inputController);
                    _controllersLock.readLock().unlock();
                }

                DataHelper.storeProps(map, cfgFile);
                done = true;
                break;
            }
        }
        if (! done) {
            String newConfigFile = _configDirectory + "/" + inputName + ".config";
            File cfgFile = new File(newConfigFile);
            if (!cfgFile.isAbsolute())
                cfgFile = new File(_context.getConfigDir(), newConfigFile);
            File parent = cfgFile.getParentFile();
            if ( (parent != null) && (!parent.exists()) )
                parent.mkdirs();

            _controllersLock.readLock().lock();
            try {
                for (int j = 0; j < _controllers.size(); j++) {
                    TunnelController controller = _controllers.get(j);
                    Properties controllerProperties = controller.getConfig(PREFIX + j + ".");
                    String curName = controllerProperties.getProperty("name");
                    if (curName != inputName) {
                        map.putAll(controllerProperties);
                    }
                }
            } finally {
                map.putAll(inputController);
                _controllersLock.readLock().unlock();
            }

            DataHelper.storeProps(map, cfgFile);
        }
    }

    /**
     * Remove the configuration of this tunnel only
     * @since 0.9.34
     */
    public synchronized void removeConfig(TunnelController tc) throws IOException {
        List<String> fileList = configFiles();
        boolean done = false;
        Properties inputController = tc.getConfig("");
        String inputName = inputController.getProperty("name");
        Properties map = new OrderedProperties();
        for (int i = 0; i < fileList.size(); i++) {
            if (inConfig(tc, fileList.get(i)) != "") {
                File cfgFile = new File(fileList.get(i));
                if (!cfgFile.isAbsolute())
                    cfgFile = new File(_context.getConfigDir(), fileList.get(i));
                File parent = cfgFile.getParentFile();
                if ( (parent != null) && (!parent.exists()) )
                    parent.mkdirs();

                _controllersLock.readLock().lock();
                try {
                    for (int j = 0; j < _controllers.size(); j++) {
                        TunnelController controller = _controllers.get(j);
                        Properties controllerProperties = controller.getConfig(PREFIX + j + ".");
                        String curName = controllerProperties.getProperty("name");
                        if (curName != inputName) {
                            map.putAll(controllerProperties);
                        }
                    }
                } finally {
                    _controllersLock.readLock().unlock();
                }

                DataHelper.storeProps(map, cfgFile);
                done = true;
            }
        }
    }

    /**
     * Check if the tunnel is present in any config file
     * @since 0.9.41
     */
    public synchronized String inConfig(TunnelController tc) throws IOException {
        List<String> fileList = configFiles();
        for (int i = 0; i < fileList.size(); i++) {
            String configFile = fileList.get(i);
            return inConfig(tc, configFile);
        }
        return "";
    }

    /**
     * Check if the tunnel is present in the specified config file
     * @since 0.9.41
     */
    public synchronized String inConfig(TunnelController tc, String configFile) throws IOException {
        File cfgFile = new File(configFile);
        if (!cfgFile.isAbsolute())
            cfgFile = new File(_context.getConfigDir(), configFile);
        File parent = cfgFile.getParentFile();
        if ( (parent != null) && (!parent.exists()) )
            parent.mkdirs();

        Properties inputController = tc.getConfig("");
        String inputName = inputController.getProperty("name");
        String outputName = "";

        Properties map = new OrderedProperties();
        _controllersLock.readLock().lock();
        try {
            for (int i = 0; i < _controllers.size(); i++) {
                TunnelController controller = _controllers.get(i);
                Properties controllerProperties = controller.getConfig(PREFIX + i + ".");
                String curName = controllerProperties.getProperty("name");
                if (curName == inputName) {
                    outputName = curName;
                }
            }
        } finally {
            _controllersLock.readLock().unlock();
        }
        return outputName;
    }

    /**
     * Return a list of config files in the config dir as strings
     *
     * @return non-null, properties loaded, one for each tunnel, or a list
     * with one member, "i2ptunnel.config"
     */
    private List<String> configFiles() {
        File folder = new File(_configDirectory);
        File[] listOfFiles = folder.listFiles();
        List<String> files = new ArrayList<String>();
        boolean shouldMigrate = _context.isRouterContext() && !SystemVersion.isAndroid();

        for (int i = 0; i < listOfFiles.length; i++) {
            if (listOfFiles[i].isFile()) {
                files.add(listOfFiles[i].getName());
            }
        }
        if (! shouldMigrate)
            files.add(_configFile);

        return files;
    }



    /**
     * Load up the config data from the file
     *
     * @return non-null, properties loaded, one for each tunnel
     * @throws IOException if unable to load from file
     */
    private synchronized List<Properties> loadConfig(File cfgFile) throws IOException {
        Properties config = new Properties();
        DataHelper.loadProps(config, cfgFile);
        List<Properties> rv = new ArrayList<Properties>();
        int i = 0;
        while (true) {
            String prefix = PREFIX + i + '.';
            Properties p = new Properties();
            for (Map.Entry<Object, Object> e : config.entrySet()) {
                String key = (String) e.getKey();
                if (key.startsWith(prefix)) {
                    key = key.substring(prefix.length());
                    String val = (String) e.getValue();
                    p.setProperty(key, val);
                }
            }
            if (p.isEmpty())
                break;
            p.setProperty("configFile", cfgFile.getAbsolutePath());
            rv.add(p);
            i++;
        }
        return rv;
    }

    /**
     * Retrieve a list of tunnels known.
     *
     * Side effect: if the tunnels have not been loaded from config yet, they
     * will be.
     *
     * @return list of TunnelController objects
     * @throws IllegalArgumentException if unable to load config from file
     */
     public List<TunnelController> getControllers() {
        List<String> fileList = configFiles();
        List<TunnelController> _tempControllers = new ArrayList<TunnelController>();
        for (int i = 0; i < fileList.size(); i++) {
            String configFile = fileList.get(i);
            _tempControllers.addAll(getControllers(configFile));
        }
        return _tempControllers;
     }

    public List<TunnelController> getControllers(String configFile) {
        synchronized (this) {
            if (!_controllersLoaded)
                loadControllers(configFile);
        }

        _controllersLock.readLock().lock();
        try {
            return new ArrayList<TunnelController>(_controllers);
        } finally {
            _controllersLock.readLock().unlock();
        }
    }


    /**
     * Note the fact that the controller is using the session so that
     * it isn't destroyed prematurely.
     *
     */
    void acquire(TunnelController controller, I2PSession session) {
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners == null) {
                owners = new HashSet<TunnelController>(2);
                _sessions.put(session, owners);
            }
            owners.add(controller);
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Acquiring session " + session + " for " + controller);

    }

    /**
     * Note the fact that the controller is no longer using the session, and if
     * no other controllers are using it, destroy the session.
     *
     */
    void release(TunnelController controller, I2PSession session) {
        boolean shouldClose = false;
        synchronized (_sessions) {
            Set<TunnelController> owners = _sessions.get(session);
            if (owners != null) {
                owners.remove(controller);
                if (owners.isEmpty()) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After releasing session " + session + " by " + controller + ", no more owners remain");
                    shouldClose = true;
                    _sessions.remove(session);
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After releasing session " + session + " by " + controller + ", " + owners.size() + " owners remain");
                    shouldClose = false;
                }
            } else {
                if (_log.shouldLog(Log.WARN))
                    _log.warn("After releasing session " + session + " by " + controller + ", no owners were even known?!");
                shouldClose = true;
            }
        }
        if (shouldClose) {
            try {
                session.destroySession();
                if (_log.shouldLog(Log.INFO))
                    _log.info("Session destroyed: " + session);
            } catch (I2PSessionException ise) {
                _log.error("Error closing the client session", ise);
            }
        }
    }

    /**
     *  @return non-null
     *  @since 0.8.8 Moved from I2PTunnelClientBase in 0.9.18
     */
    ThreadPoolExecutor getClientExecutor() {
        synchronized (_executorLock) {
            if (_executor == null)
                _executor = new CustomThreadPoolExecutor();
        }
        return _executor;
    }

    /**
     *  @since 0.8.8 Moved from I2PTunnelClientBase in 0.9.18
     */
    private void killClientExecutor() {
        synchronized (_executorLock) {
            if (_executor != null) {
                _executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
                _executor.shutdownNow();
                _executor = null;
            }
        }
        // kill the shared client, so that on restart in android
        // we won't latch onto the old one
        I2PTunnelClientBase.killSharedClient();
    }

    /**
     *  Not really needed for now but in case we want to add some hooks like afterExecute().
     *  Package private for fallback in case TCG.getInstance() is null, never instantiated
     *  but a plugin still needs it... should be rare.
     *
     *  @since 0.9.18 Moved from I2PTunnelClientBase
     */
    static class CustomThreadPoolExecutor extends ThreadPoolExecutor {
        public CustomThreadPoolExecutor() {
             super(0, Integer.MAX_VALUE, HANDLER_KEEPALIVE_MS, TimeUnit.MILLISECONDS,
                   new SynchronousQueue<Runnable>(), new CustomThreadFactory());
        }
    }

    /**
     *  Just to set the name and set Daemon
     *  @since 0.9.18 Moved from I2PTunnelClientBase
     */
    private static class CustomThreadFactory implements ThreadFactory {
        public Thread newThread(Runnable r) {
            Thread rv = Executors.defaultThreadFactory().newThread(r);
            rv.setName("I2PTunnel Client Runner " + _executorThreadCount.incrementAndGet());
            rv.setDaemon(true);
            return rv;
        }
    }
}

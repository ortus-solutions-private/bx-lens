/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ortus.boxlang.modules.bxlens.ext.CollectHandle;
import ortus.boxlang.modules.bxlens.ext.LensRegistry;
import ortus.boxlang.modules.bxlens.interceptors.BaseCollector;
import ortus.boxlang.modules.bxlens.interceptors.ILensCollector;
import ortus.boxlang.modules.bxlens.interceptors.TaskOutcomes;
import ortus.boxlang.modules.bxlens.interceptors.collectors.BifCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.ExceptionCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.FunctionCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.HttpCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.JvmCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.LifecycleCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.LogCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.OrmCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.QueryCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.ScopesCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.TemplateCollector;
import ortus.boxlang.modules.bxlens.interceptors.collectors.TransactionCollector;
import ortus.boxlang.modules.bxlens.model.IssueEngine;
import ortus.boxlang.modules.bxlens.model.LensRequest;
import ortus.boxlang.modules.bxlens.model.SecurityChecks;
import ortus.boxlang.modules.bxlens.model.Snapshot;
import ortus.boxlang.modules.bxlens.render.BarRenderer;
import ortus.boxlang.modules.bxlens.store.RequestStore;
import ortus.boxlang.modules.bxlens.util.AsyncWorker;
import ortus.boxlang.modules.bxlens.util.GlobalStats;
import ortus.boxlang.modules.bxlens.util.Json;
import ortus.boxlang.modules.bxlens.util.Keys;
import ortus.boxlang.modules.bxlens.web.WebExchange;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.RequestBoxContext;
import ortus.boxlang.runtime.logging.BoxLangLogger;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Owns everything global to bx-lens: the parsed settings, the collectors, the in-memory request history and the renderer.
 * One instance lives in the module class loader. {@link #getInstance()} never returns null, so BIFs and collectors can always call it;
 * before activation (or when Lens is disabled) it simply tracks nothing.
 */
public final class LensService {

	/**
	 * What ended the request.
	 */
	public enum Trigger {
		END,
		ERROR,
		ABORT
	}

	/**
	 * Placeholder written by lensRender() and replaced with the bar when the request ends.
	 */
	public static final String										MARKER				= "<!--bxlens:here-->";

	/** Names the server that answered. Sent only with the request id header and only when <code>history.serverHeader</code> is true. */
	public static final String										SERVER_HEADER		= "X-BxLens-Server";

	private static volatile LensService								instance;

	private volatile BoxRuntime										runtime;
	private volatile LensConfig										config				= LensConfig.defaults();
	private volatile AccessGuard									barGuard			= new AccessGuard( config, "bar.access", false );
	private volatile AccessGuard									consoleGuard		= new AccessGuard( config, "console.access", true );
	private volatile ConsoleAuth									auth				= new ConsoleAuth( config );
	private volatile TaskOutcomes									outcomes			= new TaskOutcomes();
	private volatile Licensing										licensing			= new Licensing( "" );
	private volatile LayoutStore									layout				= new LayoutStore( null );
	private volatile Map<String, Object>							baseSettings		= Map.of();
	private volatile LensConfig										baseConfig			= LensConfig.defaults();
	private volatile SettingsRegistry								settingsRegistry	= new SettingsRegistry( List.of() );
	private volatile SettingsStore									settingsStore		= new SettingsStore( null, settingsRegistry );
	private volatile ortus.boxlang.modules.bxlens.ops.McpService	mcp					= new ortus.boxlang.modules.bxlens.ops.McpService(
	    settingsStore, () -> "", () -> "" );
	private final List<ILensCollector>								allBuiltIns			= new ArrayList<>();
	private final ConsoleData										consoleData			= new ConsoleData( this );
	private final AtomicInteger										streams				= new AtomicInteger();
	private final Map<String, LensRequest>							active				= new java.util.concurrent.ConcurrentHashMap<>();
	private volatile java.util.concurrent.ScheduledExecutorService	watchdog;
	private volatile RequestStore									store				= new RequestStore( 50 );
	private volatile BarRenderer									renderer;
	private volatile String											version				= "0.0.0";
	private volatile String											moduleDir			= "";
	private final GlobalStats										stats				= new GlobalStats();
	/** The one worker for statistics, history and audit. Inline until Lens is activated. */
	private volatile AsyncWorker									worker				= new AsyncWorker( false, 1 );
	private final LensRegistry										registry			= new LensRegistry();
	/** Copy on write: a request walks it without a lock. Changes (a setting, a reload) are rare. */
	private final List<ILensCollector>								collectors			= new java.util.concurrent.CopyOnWriteArrayList<>();
	private volatile BoxLangLogger									logger;
	private volatile BoxLangLogger									auditLogger;
	private final Audit												audit				= new Audit( this );
	private volatile HeapDumper										heapDumper			= new HeapDumper();
	private final DatasourceData									datasources			= new DatasourceData();
	private final CacheData											caches				= new CacheData( this );
	private final LogData											logs				= new LogData();
	private final EnvironmentData									environment			= new EnvironmentData( this );
	private final OrmData											orm					= new OrmData();
	private final OrmTotals											ormTotals			= new OrmTotals();
	private volatile Integrations									integrations		= new Integrations();
	private final RuntimeInfo										runtimeInfo			= new RuntimeInfo();
	private final QueryStats										queryStats			= new QueryStats();
	private final ErrorStore										errors				= new ErrorStore();
	private final Reports											reports				= new Reports();
	private final AiService											ai					= new AiService( this );
	private final ortus.boxlang.modules.bxlens.ops.AgentService		agents				= new ortus.boxlang.modules.bxlens.ops.AgentService( this );
	private final ortus.boxlang.modules.bxlens.util.ServerIdentity	identity			= new ortus.boxlang.modules.bxlens.util.ServerIdentity();
	private volatile java.nio.file.Path								storeDir;
	private volatile long											lastFlush;

	private LensService() {
	}

	/**
	 * The service singleton.
	 */
	public static LensService getInstance() {
		LensService i = instance;
		if ( i == null ) {
			synchronized ( LensService.class ) {
				if ( instance == null ) {
					instance = new LensService();
				}
				i = instance;
			}
		}
		return i;
	}

	/**
	 * Activate with the module settings. Called from ModuleConfig.bx on load. Safe to call again on reload.
	 *
	 * @param runtime   the runtime
	 * @param settings  the module settings
	 * @param moduleDir physical path of the module folder
	 * @param version   module version
	 */
	public synchronized void activate( BoxRuntime runtime, Map<?, ?> settings, String moduleDir, String version ) {
		shutdown();
		this.runtime		= runtime;
		this.version		= version == null ? "" : version;
		this.moduleDir		= moduleDir == null ? "" : moduleDir;
		this.baseSettings	= LensConfig.overlay( settings, null );
		this.baseConfig		= new LensConfig( settings );
		allBuiltIns.clear();
		allBuiltIns.addAll( builtIns() );
		List<String> ids = new ArrayList<>(
		    List.of( "executors", "tasks", "datasources", "caches", "logfiles", "environment", "queries", "inflight", "errors", "reports", "ask", "orm",
		        "system", "threads", "modules", "configuration", "ai" ) );
		allBuiltIns.forEach( c -> {
			if ( !ids.contains( c.id() ) && ! ( c instanceof LifecycleCollector ) ) {
				ids.add( c.id() );
			}
		} );
		this.settingsRegistry = new SettingsRegistry( ids );
		Path overridesFile = null;
		try {
			String custom = this.baseConfig.getString( "console.overridesFile", "" );
			overridesFile = custom.isBlank() ? runtime.getRuntimeHome().resolve( "config" ).resolve( "bxlens-settings.json" ) : Path.of( custom );
		} catch ( Throwable t ) {
			// No home: overrides live in memory only
		}
		this.settingsStore	= new SettingsStore( overridesFile, this.settingsRegistry );
		this.config			= new LensConfig( LensConfig.overlay( settings, this.settingsStore.get() ) );
		this.mcp.shutdown();
		// dev.mcpBuiltinBase serves the builtin documentation servers from a stand-in (the test harness): base/id for each. It is read from
		// boxlang.json only; the console cannot change it.
		this.mcp = new ortus.boxlang.modules.bxlens.ops.McpService( this.settingsStore, () -> this.baseConfig.getString( "dev.mcpBuiltinBase", "" ),
		    () -> this.agents.invocationPath() );
		if ( this.settingsStore.skipped() > 0 ) {
			getLogger().warn( "bx-lens: ignored {} invalid entries in the settings overrides file [{}]", this.settingsStore.skipped(), overridesFile );
		}
		this.identity.configure( this.config.getString( "server.name", "" ), this.config.getString( "server.address", "" ),
		    this.config.getString( "server.id", "" ) );
		this.queryStats.identify( () -> this.identity.get().toMap() );
		this.reports.identify( () -> this.identity.get().toMap() );
		this.errors.identify( () -> this.identity.get().toMap() );
		this.barGuard		= new AccessGuard( this.config, "bar.access", this.config.getBool( "bar.allowAllIPs", false ) );
		this.consoleGuard	= new AccessGuard( this.config, "console.access", true );
		this.auth			= new ConsoleAuth( this.config );
		this.auth.onEnd( this.agents::drop );
		this.outcomes	= new TaskOutcomes();
		this.licensing	= new Licensing( this.config.getString( "dev.license", "" ) );
		Path layoutFile = null;
		try {
			layoutFile = runtime.getRuntimeHome().resolve( "config" ).resolve( "bxlens-layout.json" );
		} catch ( Throwable t ) {
			// No home: the layout lives in memory only
		}
		this.layout = new LayoutStore( layoutFile );
		if ( this.config.consoleEnabled ) {
			runtime.getInterceptorService().register( this.outcomes );
		}
		this.worker	= new AsyncWorker( this.config.getBool( "async.enabled", true ), this.config.getInt( "async.queueSize", 2000 ) );
		this.store	= new RequestStore( this.config.maxRequests );
		loadStore();
		this.renderer = new BarRenderer( Path.of( moduleDir ).resolve( "assets" ), this.config.getBool( "dev.reloadAssets", false ) );
		if ( !this.renderer.isComplete() ) {
			getLogger().warn( "bx-lens assets are missing under [{}]. The bar will not render.", moduleDir );
		}
		if ( !this.config.barEnabled && !this.config.consoleEnabled ) {
			getLogger().info( "bx-lens is installed but off. Set bar.enabled or console.enabled in the module settings to turn it on." );
		}
		if ( this.barGuard.isDowngraded() ) {
			getLogger().error(
			    "bx-lens: bar.access is [all] but bar.allowAllIPs is not true. Showing the bar to loopback only. Set bar.allowAllIPs to true to confirm you want every IP to see request internals." );
		}
		if ( this.config.consoleEnabled && this.config.consolePassword().isBlank() ) {
			getLogger().error( "bx-lens: console.enabled is true but console.password is empty or cannot be decrypted. The console stays unavailable." );
		}
		if ( this.config.consoleEnabled && this.consoleGuard.isOpenToAll() ) {
			getLogger().warn( "bx-lens: console.access is [all]. Use HTTPS and a strong password." );
		}
		reconcileCollectors();
		startWatchdog();
		announceRegister();
		getLogger().info( "bx-lens {} active: bar={}, console={}, collect={}, collectors={}", this.version, this.config.barEnabled, this.config.consoleEnabled,
		    this.config.collectLevel, collectorIds() );
	}

	/**
	 * Unregister all collectors and clear history.
	 */
	public synchronized void shutdown() {
		// Let the queued statistics land before they are saved or cleared
		worker.shutdown( 3000 );
		worker = new AsyncWorker( false, 1 );
		flushStore( true );
		heapDumper.shutdown();
		heapDumper = new HeapDumper();
		if ( watchdog != null ) {
			watchdog.shutdownNow();
			watchdog = null;
		}
		active.clear();
		try {
			if ( runtime != null ) {
				runtime.getInterceptorService().unregister( outcomes );
			}
		} catch ( Throwable t ) {
			// Not registered
		}
		synchronized ( collectors ) {
			for ( ILensCollector c : collectors ) {
				try {
					if ( c instanceof BaseCollector bc && runtime != null ) {
						runtime.getInterceptorService().unregister( bc );
					}
				} catch ( Throwable t ) {
					// Already gone
				}
			}
			collectors.clear();
		}
		store.clear();
		registry.clear();
		ormTotals.reset();
		try {
			ai.shutdown();
			agents.shutdown();
			mcp.shutdown();
		} catch ( Throwable t ) {
			// Nothing to stop
		}
		try {
			datasources.shutdown();
		} catch ( Throwable t ) {
			// Nothing to undo
		}
	}

	/**
	 * Register an additional collector, for example from a Java extension.
	 */
	public void register( ILensCollector collector ) {
		if ( collector instanceof BaseCollector bc && runtime != null ) {
			runtime.getInterceptorService().register( bc );
		}
		collectors.add( collector );
	}

	/**
	 * Remove a collector.
	 */
	public void unregister( ILensCollector collector ) {
		try {
			if ( collector instanceof BaseCollector bc && runtime != null ) {
				runtime.getInterceptorService().unregister( bc );
			}
		} catch ( Throwable t ) {
			// Already gone
		}
		collectors.remove( collector );
	}

	/**
	 * Make the registered collectors match the settings: add the ones now wanted, remove the ones no longer wanted.
	 */
	private void reconcileCollectors() {
		synchronized ( collectors ) {
			for ( ILensCollector c : allBuiltIns ) {
				boolean	heavyBlocked	= this.config.light && c.heavy();
				boolean	switchedOn		= c.integration() != null ? this.integrations.on( c.integration(), this.config )
				    : this.config.isCollectorEnabled( c.id(), c.enabledByDefault() );
				boolean	want			= c instanceof LifecycleCollector || ( !heavyBlocked && switchedOn );
				boolean	has				= collectors.contains( c );
				if ( want && !has ) {
					register( c );
				} else if ( !want && has ) {
					unregister( c );
				}
			}
		}
	}

	/**
	 * A module was loaded or unloaded: register the listeners of integrations whose module is now installed, remove the ones whose module is gone.
	 * Called from the module events, so no restart is needed.
	 */
	public synchronized void reconcileIntegrations() {
		if ( runtime != null ) {
			reconcileCollectors();
		}
	}

	/**
	 * Switch an integration on or off, live. Switching on needs its module.
	 *
	 * @throws IllegalArgumentException when the id is not an integration
	 * @throws IllegalStateException    when the module is not installed
	 */
	public synchronized void setIntegration( String id, boolean enabled ) throws java.io.IOException {
		Integrations.Def d = Integrations.get( id );
		if ( d == null ) {
			throw new IllegalArgumentException( "Unknown integration: " + id );
		}
		if ( enabled && !integrations.isInstalled( d ) ) {
			throw new IllegalStateException( "Install " + d.install() + " to enable " + d.name() );
		}
		changeSettings( Map.of( d.settingKey(), enabled ) );
	}

	public Integrations getIntegrations() {
		return integrations;
	}

	/** Replace how installed modules are found (tests). */
	public void setIntegrations( Integrations integrations ) {
		this.integrations = integrations;
	}

	/** Replace the license (tests). */
	public void setLicensing( Licensing licensing ) {
		this.licensing = licensing;
	}

	/** Replace the MCP service (tests). */
	public void setMcp( ortus.boxlang.modules.bxlens.ops.McpService mcp ) {
		this.mcp.shutdown();
		this.mcp = mcp;
	}

	/** Replace the settings (tests). Call {@link #setConfig} with {@code LensConfig.defaults()} to put them back. */
	public void setConfig( LensConfig config ) {
		this.config = config;
	}

	public OrmTotals getOrmTotals() {
		return ormTotals;
	}

	public SettingsRegistry getSettingsRegistry() {
		return settingsRegistry;
	}

	public SettingsStore getSettingsStore() {
		return settingsStore;
	}

	/**
	 * Change settings from the console. <code>null</code> removes the override for that key. Everything is checked first: a bad value
	 * changes nothing.
	 *
	 * @throws IllegalArgumentException when a key or value is not valid
	 */
	public synchronized void changeSettings( Map<String, Object> changes ) throws java.io.IOException {
		Map<String, Object> next = new java.util.LinkedHashMap<>( settingsStore.get() );
		for ( Map.Entry<String, Object> e : changes.entrySet() ) {
			SettingsRegistry.Def d = settingsRegistry.get( e.getKey() );
			if ( d == null ) {
				throw new IllegalArgumentException( "Unknown setting: " + e.getKey() );
			}
			if ( e.getValue() == null ) {
				next.remove( d.key() );
			} else {
				next.put( d.key(), e.getValue() );
			}
		}
		settingsStore.set( next );
		applySettings();
	}

	/**
	 * Drop one override, or all of them when the key is null.
	 */
	public synchronized void resetSettings( String key ) throws java.io.IOException {
		Map<String, Object> next = new java.util.LinkedHashMap<>( settingsStore.get() );
		if ( key == null || key.isBlank() ) {
			next.clear();
		} else {
			SettingsRegistry.Def d = settingsRegistry.get( key );
			if ( d == null ) {
				throw new IllegalArgumentException( "Unknown setting: " + key );
			}
			next.remove( d.key() );
		}
		settingsStore.set( next );
		applySettings();
	}

	private void applySettings() {
		this.config = new LensConfig( LensConfig.overlay( baseSettings, settingsStore.get() ) );
		reconcileCollectors();
	}

	/**
	 * Every known setting with its effective value, where it came from and whether it can be changed. Secrets are never included.
	 */
	public List<Map<String, Object>> settingsView() {
		List<Map<String, Object>> out = new ArrayList<>();
		for ( SettingsRegistry.Def d : settingsRegistry.all() ) {
			Map<String, Object>	m			= d.toMap();
			Object				value		= config.get( d.key() );
			Object				configured	= baseConfig.get( d.key() );
			if ( "secret".equals( d.type() ) ) {
				m.put( "value", value == null || value.toString().isBlank() ? "not set" : "set" );
				m.put( "configured", m.get( "value" ) );
			} else {
				if ( d.key().startsWith( "collectors." ) && d.key().endsWith( ".enabled" ) && value == null ) {
					value = config.isCollectorEnabled( d.key().split( "\\." )[ 1 ], ( Boolean ) d.def() );
				}
				if ( configured == null && d.key().startsWith( "collectors." ) && d.key().endsWith( ".enabled" ) ) {
					configured = baseConfig.isCollectorEnabled( d.key().split( "\\." )[ 1 ], ( Boolean ) d.def() );
				}
				if ( "history.trackNonHtml".equals( d.key() ) ) {
					// The effective value depends on the collect level
					value		= config.trackNonHtml;
					configured	= baseConfig.trackNonHtml;
				}
				m.put( "value", value == null ? d.def() : value );
				m.put( "configured", configured == null ? d.def() : configured );
			}
			m.put( "source", settingsStore.get().containsKey( d.key() ) ? "override" : "config" );
			out.add( m );
		}
		return out;
	}

	/**
	 * Let applications and modules declare panels. Fires the onLensRegister interception point.
	 */
	public void announceRegister() {
		if ( runtime == null ) {
			return;
		}
		try {
			registry.clear();
			runtime.getInterceptorService().announce( Keys.onLensRegister, Struct.of( "registry", registry ) );
		} catch ( Throwable t ) {
			getLogger().warn( "onLensRegister listener failed: {}", t.toString() );
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Request lifecycle
	// ---------------------------------------------------------------------------------------------

	/**
	 * Start tracking a request when Lens is enabled, the caller is allowed and the path is not excluded.
	 *
	 * @return the tracked request or null
	 */
	public LensRequest begin( RequestBoxContext rc ) {
		if ( !config.active || rc == null ) {
			return null;
		}
		LensRequest existing = rc.getAttachment( Keys.requestAttach );
		if ( existing != null ) {
			return existing;
		}
		WebExchange ex = WebExchange.of( rc );
		if ( ex == null ) {
			return null;
		}
		String uri = ex.uri();
		if ( config.isExcluded( uri ) ) {
			return null;
		}
		// The bar only shows for allowed callers. The console collects every request so production traffic is visible to it.
		boolean showBar = config.barEnabled && barGuard.isAllowed( ex.remoteAddr(), ex.host(), ex.requestHeader( barGuard.requiredHeader() ) );
		if ( !showBar && !config.consoleEnabled ) {
			return null;
		}
		LensRequest req = new LensRequest();
		req.showBar			= showBar;
		req.requestContext	= rc;
		req.method			= ex.method();
		req.uri				= uri;
		req.queryString		= ex.queryString();
		req.remoteAddr		= ex.remoteAddr();
		req.host			= ex.host();
		String ua = ex.requestHeader( "User-Agent" );
		req.userAgent	= ua == null ? "" : ua;
		req.template	= uri;
		req.thread		= Thread.currentThread();
		// The cached identity: no lookup happens here
		ortus.boxlang.modules.bxlens.util.ServerIdentity.Info me = identity.get();
		req.serverHost	= me.host();
		req.serverIp	= me.ip();
		req.serverId	= me.id();
		if ( licensing.has( "cost" ) ) {
			req.data.put( "_costStart", ortus.boxlang.modules.bxlens.util.Cost.begin() );
		}
		active.put( req.id, req );
		rc.putAttachment( Keys.requestAttach, req );
		try {
			if ( showBar || config.getBool( "history.headerAlways", true ) ) {
				ex.setResponseHeader( config.idHeader, req.id );
				// Which server answered, only next to the id and only when asked for: it names a machine to whoever sees the response
				if ( config.getBool( "history.serverHeader", false ) ) {
					ex.setResponseHeader( SERVER_HEADER, req.serverId );
				}
			}
		} catch ( Throwable t ) {
			// Headers already sent
		}
		tie( rc, req );
		for ( ILensCollector c : collectors ) {
			try {
				c.onRequestStart( req );
			} catch ( Throwable t ) {
				getLogger().debug( "Collector [{}] failed on request start: {}", c.id(), t.toString() );
			}
		}
		announce( Keys.onLensRequestStart, () -> Struct.of( "context", rc, "requestId", req.id ) );
		return req;
	}

	/**
	 * Make the request id available to the application and to log lines: <code>request.bxlens.id</code>, and the logging context key
	 * <code>requestId</code> (SLF4J MDC) on the request thread, so a log pattern with <code>%X{requestId}</code> prints it.
	 */
	private void tie( RequestBoxContext rc, LensRequest req ) {
		try {
			IStruct mine = new Struct();
			mine.put( Keys.idKey, req.id );
			rc.getScopeNearby( ortus.boxlang.runtime.scopes.RequestScope.name ).put( Keys.bxlens, mine );
		} catch ( Throwable t ) {
			// The request scope is optional
		}
		try {
			org.slf4j.MDC.put( "requestId", req.id );
		} catch ( Throwable t ) {
			// No logging context
		}
	}

	/**
	 * Finish a request: stop timing, let collectors gather final data, record the totals for the console and, for requests that belong in the
	 * history, find issues and keep the request. The JSON for the page is not built here. It is built when the bar is rendered for a caller
	 * who may see it, or when the console opens the request.
	 */
	public void finish( RequestBoxContext rc, Trigger trigger ) {
		LensRequest req = rc.getAttachment( Keys.requestAttach );
		if ( req == null || !req.finished.compareAndSet( false, true ) ) {
			return;
		}
		final LensConfig cfg = this.config;
		try {
			org.slf4j.MDC.remove( "requestId" );
		} catch ( Throwable t ) {
			// No logging context
		}
		req.endNanos = System.nanoTime();
		active.remove( req.id );
		Object costStart = req.data.remove( "_costStart" );
		if ( costStart instanceof ortus.boxlang.modules.bxlens.util.Cost.Start cs ) {
			Map<String, Object> cost = ortus.boxlang.modules.bxlens.util.Cost.since( cs );
			if ( cost != null ) {
				req.data.put( "cost", cost );
			}
		}
		req.closeAll();
		WebExchange ex = WebExchange.of( rc );
		if ( ex != null ) {
			try {
				req.status = ex.status();
				String ct = ex.responseHeader( "Content-Type" );
				req.contentType = ct == null ? "" : ct;
			} catch ( Throwable t ) {
				// Exchange recycled
			}
		}
		if ( trigger == Trigger.ERROR && req.status < 400 ) {
			req.status = 500;
		}
		req.html = cfg.isInjectable( req.contentType );
		try {
			req.appName = rc.getApplicationListener().getAppName().getName();
		} catch ( Throwable t ) {
			req.appName = "";
		}
		// A request that will not be kept in the history and will not show the bar needs none of the work that only feeds the page
		final boolean keep = req.html || cfg.trackNonHtml;
		for ( ILensCollector c : collectors ) {
			if ( !keep && c.snapshotOnly() ) {
				continue;
			}
			try {
				c.onRequestFinish( req );
			} catch ( Throwable t ) {
				getLogger().debug( "Collector [{}] failed on request finish [{}]: {}", c.id(), req.id, t.toString() );
			}
		}
		announce( Keys.onLensCollect, () -> Struct.of( "context", rc, "requestId", req.id, "lens", new CollectHandle( req ) ) );
		stats.recordRequest( Math.round( req.durationNs() / 1_000_000.0 ) );
		// The console totals read the finished request directly. They are not needed to answer this request, so a worker does them
		worker.submit( () -> {
			queryStats.record( req, cfg.slowQueryMs );
			errors.record( req, cfg );
			reports.record( req, cfg.slowRequestMs );
		} );

		// What the page and the console need from the web request is copied now, while it is still there. Nothing is analyzed or serialized yet
		if ( keep && ex != null ) {
			try {
				req.url = ex.url();
				if ( !cfg.light ) {
					req.requestHeaders = ex.requestHeaders();
				}
				req.responseHeaders = ex.responseHeaders();
				if ( cfg.consoleEnabled && SecurityChecks.applies( req, cfg ) ) {
					req.data.put( "_cookies", ex.responseCookies() );
					req.data.put( "_secure", ex.secure() );
				}
			} catch ( Throwable t ) {
				getLogger().debug( "Could not copy the response of request [{}]: {}", req.id, t.toString() );
			}
		}
		// The history serves the console. A bar only installation keeps nothing: the page it renders is all anybody sees
		RequestStore.Entry entry = null;
		if ( keep && cfg.consoleEnabled ) {
			entry = entryFor( req, cfg );
			final RequestStore.Entry stored = entry;
			worker.submit( () -> store.add( stored ) );
		}
		if ( keep && trigger == Trigger.END && req.showBar && req.html && renderer != null && renderer.isComplete() && ex != null
		    && !ex.responseStarted() ) {
			try {
				StringBuffer	buffer		= rc.getBuffer();
				int				markerAt	= buffer.indexOf( MARKER );
				if ( ( cfg.inject || markerAt >= 0 ) && req.injected.compareAndSet( false, true ) ) {
					boolean	consoleOk	= cfg.consoleEnabled
					    && consoleGuard.isAllowed( ex.remoteAddr(), ex.host(), ex.requestHeader( consoleGuard.requiredHeader() ) );
					// The payload is built here, and only here, for a caller who is allowed to see the bar
					String	json		= entry != null ? entry.json() : snapshotJson( req, cfg );
					String	block		= renderer.render( pagePayload( json, consoleOk ? "/~bxlens/index.bxm" : "" ) );
					if ( markerAt >= 0 ) {
						buffer.replace( markerAt, markerAt + MARKER.length(), block );
					} else {
						BarRenderer.insert( buffer, block );
					}
				}
			} catch ( Throwable t ) {
				getLogger().warn( "bx-lens could not inject the bar for request [{}]: {}", req.id, t.toString() );
			}
		}
		announce( Keys.onLensRequestFinish, () -> Struct.of( "context", rc, "requestId", req.id ) );
		req.release();
	}

	/**
	 * The history entry of a finished request. Its payloads and the issue analysis are built when somebody asks.
	 */
	private RequestStore.Entry entryFor( LensRequest req, LensConfig cfg ) {
		return new RequestStore.Entry( req.id, () -> Snapshot.summary( req, cfg ), () -> snapshotJson( req, cfg ), () -> {
			try {
				analyze( req, cfg );
				return Json.write( Snapshot.build( req, cfg, true ) );
			} catch ( Throwable t ) {
				getLogger().warn( "bx-lens could not build request [{}] for the console: {}", req.id, t.toString() );
				return Json.write( Map.of( "error", "This request could not be built" ) );
			}
		}, () -> {
			analyze( req, cfg );
			return Snapshot.summaryWithIssues( req, cfg );
		} ).server( req.serverId );
	}

	/**
	 * Find the issues of a request: exceptions, N+1 and slow queries, slow templates, error statuses and security notes. Only the console
	 * wants them, so this runs when the console looks at the request, once. The bar never shows them.
	 */
	private void analyze( LensRequest req, LensConfig cfg ) {
		synchronized ( req ) {
			if ( req.analyzed ) {
				return;
			}
			req.analyzed = true;
			IssueEngine.analyze( req, cfg );
			try {
				if ( req.data.get( "_cookies" ) instanceof List<?> cookies && req.responseHeaders != null ) {
					@SuppressWarnings( "unchecked" )
					List<SecurityChecks.Cookie> typed = ( List<SecurityChecks.Cookie> ) cookies;
					SecurityChecks.analyze( req, cfg, req.responseHeaders, typed, Boolean.TRUE.equals( req.data.get( "_secure" ) ) );
				}
			} catch ( Throwable t ) {
				getLogger().debug( "Security checks failed: {}", t.toString() );
			}
			applySlowSample( req );
		}
	}

	/**
	 * A request that never ended by itself (a thread that was killed, a lost event, a hang) is finished here by the watchdog once it is older
	 * than <code>request.maxMinutes</code>: its open spans are closed with an estimate and marked, it is kept in the history as unfinished and
	 * it leaves the list of running requests. Nothing is written to its response.
	 */
	private void sweepStale( LensConfig cfg ) {
		long cut = System.nanoTime() - cfg.requestMaxMinutes * 60_000_000_000L;
		for ( LensRequest r : active.values() ) {
			if ( r.startNanos < cut && r.finished.compareAndSet( false, true ) ) {
				try {
					active.remove( r.id );
					finishUnfinished( r, cfg );
				} catch ( Throwable t ) {
					getLogger().debug( "Could not finish the stale request [{}]: {}", r.id, t.toString() );
				}
			}
		}
	}

	void finishUnfinished( LensRequest req, LensConfig cfg ) {
		req.endNanos	= System.nanoTime();
		req.unfinished	= true;
		req.status		= 0;
		req.html		= false;
		req.closeAll();
		worker.submit( () -> {
			queryStats.record( req, cfg.slowQueryMs );
			errors.record( req, cfg );
			reports.record( req, cfg.slowRequestMs );
			if ( cfg.consoleEnabled ) {
				store.add( entryFor( req, cfg ) );
			}
		} );
		req.release();
	}

	/**
	 * Run something off the request thread, in order. Never blocks and never throws.
	 */
	public void async( Runnable task ) {
		worker.submit( task );
	}

	/**
	 * Wait (a little) until the work queued so far has run. Readers of the statistics call it first, so what they show includes the request
	 * that just ended.
	 */
	public void sync() {
		worker.barrier( 1000 );
	}

	/**
	 * The state of the worker, for the console and for diagnostics.
	 */
	public Map<String, Object> asyncStats() {
		AsyncWorker			w	= worker;
		Map<String, Object>	m	= new LinkedHashMap<>();
		m.put( "enabled", w.enabled() );
		m.put( "depth", w.depth() );
		m.put( "capacity", w.capacity() );
		m.put( "dropped", w.dropped() );
		m.put( "processed", w.processed() );
		m.put( "failed", w.failed() );
		m.put( "server", identity.get().toMap() );
		return m;
	}

	private String snapshotJson( LensRequest req, LensConfig cfg ) {
		try {
			return Json.write( Snapshot.build( req, cfg ) );
		} catch ( Throwable t ) {
			getLogger().warn( "bx-lens could not build the request [{}]: {}", req.id, t.toString() );
			return Json.write( Map.of( "error", "This request could not be built" ) );
		}
	}

	/**
	 * Once a request runs longer than the slow request limit, take one stack sample of its thread, so the issue can say where it was stuck.
	 */
	/** Requests kept in memory without a Plus license. */
	public static final int	FREE_HISTORY	= 25;

	private int				tick;

	private void startWatchdog() {
		watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor( r -> {
			Thread t = new Thread( r, "bxlens-watchdog" );
			t.setDaemon( true );
			return t;
		} );
		watchdog.scheduleWithFixedDelay( () -> {
			try {
				if ( tick % 25 == 24 ) {
					sweepStale( config );
				}
				if ( ++tick % 25 == 0 ) {
					if ( config.consoleEnabled ) {
						datasources.attachAll();
					}
					int allowed = licensing.has( "fullHistory" ) ? config.maxRequests : Math.min( config.maxRequests, FREE_HISTORY );
					if ( store.capacity() != allowed ) {
						store.setCapacity( allowed );
					}
					if ( System.currentTimeMillis() - lastFlush >= Math.max( 5, config.getInt( "store.flushSeconds", 30 ) ) * 1000L ) {
						lastFlush = System.currentTimeMillis();
						flushStore( false );
					}
				}
				if ( !config.active || config.slowRequestMs <= 0 || !config.getBool( "checks.slowSample", true ) || !licensing.has( "cost" ) ) {
					return;
				}
				long now = System.nanoTime();
				for ( LensRequest r : active.values() ) {
					if ( !r.data.containsKey( "slowSample" ) && ( now - r.startNanos ) / 1_000_000L >= config.slowRequestMs && r.thread != null
					    && r.thread.isAlive() ) {
						List<Map<String, Object>> frames = new ArrayList<>();
						for ( StackTraceElement e : r.thread.getStackTrace() ) {
							if ( frames.size() >= 40 ) {
								break;
							}
							Map<String, Object> f = new LinkedHashMap<>();
							f.put( "text", e.toString() );
							String file = e.getFileName() == null ? "" : e.getFileName().toLowerCase();
							f.put( "bx", file.endsWith( ".bx" ) || file.endsWith( ".bxm" ) || file.endsWith( ".bxs" ) || file.endsWith( ".cfc" )
							    || file.endsWith( ".cfm" ) );
							frames.add( f );
						}
						Map<String, Object> sample = new LinkedHashMap<>();
						sample.put( "atMs", Math.round( ( now - r.startNanos ) / 1_000_000.0 ) );
						sample.put( "frames", frames );
						r.data.put( "slowSample", sample );
					}
				}
			} catch ( Throwable t ) {
				// The watchdog must never stop
			}
		}, 200, 200, java.util.concurrent.TimeUnit.MILLISECONDS );
	}

	@SuppressWarnings( "unchecked" )
	private void applySlowSample( LensRequest req ) {
		Object s = req.data.get( "slowSample" );
		if ( ! ( s instanceof Map<?, ?> sample ) ) {
			return;
		}
		Object frames = sample.get( "frames" );
		if ( ! ( frames instanceof List<?> list ) ) {
			return;
		}
		for ( Object o : list ) {
			if ( o instanceof Map<?, ?> f && Boolean.TRUE.equals( f.get( "bx" ) ) ) {
				String					text	= String.valueOf( f.get( "text" ) );
				java.util.regex.Matcher	m		= java.util.regex.Pattern.compile( "\\(([^()]*\\.(?:bxm|bxs|bx|cfc|cfm)):(\\d+)\\)" ).matcher( text );
				if ( m.find() ) {
					synchronized ( req.issues ) {
						for ( Map<String, Object> issue : req.issues ) {
							if ( "Slow request".equals( issue.get( "title" ) ) ) {
								issue.put( "detail", issue.get( "detail" ) + ". At " + sample.get( "atMs" ) + " ms it was in "
								    + m.group( 1 ).replaceAll( ".*/", "" ) + ":" + m.group( 2 ) );
								issue.put( "file", m.group( 1 ) );
								issue.put( "line", Integer.parseInt( m.group( 2 ) ) );
							}
						}
					}
				}
				return;
			}
		}
	}

	private static boolean isBoxLangFile( String name ) {
		return name.indexOf( '(' ) < 0 && name.indexOf( ')' ) < 0
		    && ( name.endsWith( ".bxm" ) || name.endsWith( ".bxs" ) || name.endsWith( ".bx" ) || name.endsWith( ".cfc" ) || name.endsWith( ".cfm" ) );
	}

	/**
	 * The tracked request for a context, or null when this request is not tracked.
	 */
	public LensRequest current( IBoxContext context ) {
		if ( context == null ) {
			return null;
		}
		RequestBoxContext rc = context.getRequestContext();
		if ( rc == null ) {
			return null;
		}
		LensRequest r = rc.getAttachment( Keys.requestAttach );
		return r != null && r.enabled ? r : null;
	}

	/**
	 * Build the JSON for the page: the request snapshot, recent history and UI settings.
	 */
	String pagePayload( String requestJson, String consoleUrl ) {
		Map<String, Object> ui = new LinkedHashMap<>();
		ui.put( "version", version );
		ui.put( "theme", config.getString( "ui.theme", "auto" ) );
		ui.put( "startOpen", config.getBool( "ui.startOpen", false ) );
		ui.put( "autoOpenOnException", config.getBool( "ui.autoOpenOnException", true ) );
		ui.put( "defaultTab", config.getString( "ui.defaultTab", "timeline" ) );
		ui.put( "height", config.getInt( "ui.height", 360 ) );
		ui.put( "allowDetach", config.getBool( "ui.allowDetach", true ) );
		ui.put( "hotkey", config.getString( "ui.hotkey", "Ctrl+`" ) );
		ui.put( "editorLink", config.editorLink() );
		ui.put( "remoteBase", config.getString( "editor.remoteBase", "" ) );
		ui.put( "localBase", config.getString( "editor.localBase", "" ) );
		ui.put( "maxRequests", config.maxRequests );
		ui.put( "layout", layout.get() );
		ui.put( "hiddenTabs", config.hiddenTabs );
		ui.put( "plus", licensing.features() );
		ui.put( "consoleUrl", consoleUrl );
		// The Ask link of the bar opens the console with the assistant, so it needs a console the caller can reach and an assistant that works
		ui.put( "agent", !consoleUrl.isEmpty() && ai.canCall() );
		ui.put( "slowQueryMs", config.slowQueryMs );
		ui.put( "slowRequestMs", config.slowRequestMs );
		Map<String, Object> page = new LinkedHashMap<>();
		page.put( "ui", ui );
		page.put( "declared", registry.list() );
		StringBuilder sb = new StringBuilder( requestJson.length() + 4096 );
		sb.append( "{\"data\":" ).append( requestJson );
		String pageJson = Json.write( page );
		sb.append( ',' ).append( pageJson, 1, pageJson.length() );
		return sb.toString();
	}

	/**
	 * Announce a Lens point. The data is built only when something listens.
	 */
	private void announce( ortus.boxlang.runtime.scopes.Key point, java.util.function.Supplier<IStruct> data ) {
		try {
			if ( runtime != null ) {
				runtime.getInterceptorService().announce( point, data );
			}
		} catch ( Throwable t ) {
			getLogger().debug( "Listener for [{}] failed: {}", point.getName(), t.toString() );
		}
	}

	private List<ILensCollector> builtIns() {
		return List.of( new LifecycleCollector(), new TemplateCollector(), new FunctionCollector(), new QueryCollector(), new HttpCollector(),
		    new ExceptionCollector(), new LogCollector(), new TransactionCollector(), new ScopesCollector(), new JvmCollector(),
		    new BifCollector(), new OrmCollector() );
	}

	private List<String> collectorIds() {
		List<String> ids = new ArrayList<>();
		synchronized ( collectors ) {
			collectors.forEach( c -> ids.add( c.id() ) );
		}
		return ids;
	}

	// ---------------------------------------------------------------------------------------------
	// Accessors
	// ---------------------------------------------------------------------------------------------

	public LensConfig getConfig() {
		return config;
	}

	public GlobalStats getStats() {
		return stats;
	}

	public RequestStore getStore() {
		return store;
	}

	public LensRegistry getRegistry() {
		return registry;
	}

	public String getVersion() {
		return version;
	}

	public boolean isEnabled() {
		return config.active;
	}

	public AccessGuard getConsoleGuard() {
		return consoleGuard;
	}

	public AccessGuard getBarGuard() {
		return barGuard;
	}

	public ConsoleAuth getAuth() {
		return auth;
	}

	public LayoutStore getLayout() {
		return layout;
	}

	public Licensing getLicensing() {
		return licensing;
	}

	public TaskOutcomes getOutcomes() {
		return outcomes;
	}

	public ConsoleData getData() {
		return consoleData;
	}

	public AtomicInteger getStreams() {
		return streams;
	}

	public String getModuleDir() {
		return moduleDir;
	}

	public List<ILensCollector> getCollectors() {
		synchronized ( collectors ) {
			return new ArrayList<>( collectors );
		}
	}

	/**
	 * The bxLens logger.
	 */
	public BoxLangLogger auditLogger() {
		BoxLangLogger l = auditLogger;
		if ( l == null ) {
			l			= ( runtime != null ? runtime : BoxRuntime.getInstance() ).getLoggingService().getLogger( "bxlens-audit" );
			auditLogger	= l;
		}
		return l;
	}

	public AiService getAi() {
		return ai;
	}

	/**
	 * Lensy: conversations, approvals, the documentation index.
	 */
	public ortus.boxlang.modules.bxlens.ops.AgentService getAgents() {
		return agents;
	}

	/**
	 * The MCP servers Lensy may ask: the list an admin keeps in the console, what was found on them, and the calls.
	 */
	public ortus.boxlang.modules.bxlens.ops.McpService getMcp() {
		return mcp;
	}

	public ErrorStore getErrors() {
		return errors;
	}

	public Reports getReports() {
		return reports;
	}

	/**
	 * The identity of this server, detected once and refreshed every few minutes. Reading it does no lookup.
	 */
	public ortus.boxlang.modules.bxlens.util.ServerIdentity getIdentity() {
		return identity;
	}

	/**
	 * Is the Plus disk store in use? It needs the license, <code>store.enabled</code> and a folder to write to.
	 */
	public boolean diskStoreOn() {
		return storeDir != null && config.getBool( "store.enabled", true ) && licensing.has( "diskStore" );
	}

	private void loadStore() {
		String dir = baseConfig.getString( "store.dir", "" );
		try {
			storeDir = dir.isBlank() ? runtime.getRuntimeHome().resolve( "lens-data" ) : java.nio.file.Path.of( dir );
		} catch ( Throwable t ) {
			storeDir = null;
		}
		int hours = Math.max( 1, baseConfig.getInt( "store.retentionHours", 72 ) );
		reports.keepMinutes( diskStoreOn() ? hours * 60 : 60 );
		if ( !diskStoreOn() ) {
			return;
		}
		try {
			java.nio.file.Path f = storeDir.resolve( "reports.json" );
			if ( java.nio.file.Files.exists( f ) ) {
				reports.load( ortus.boxlang.modules.bxlens.util.Plain.map( ortus.boxlang.modules.bxlens.util.Plain
				    .parse( java.nio.file.Files.readString( f, java.nio.charset.StandardCharsets.UTF_8 ) ) ) );
			}
			f = storeDir.resolve( "errors.json" );
			if ( java.nio.file.Files.exists( f ) ) {
				errors.load( ErrorStore.groupsOf( ortus.boxlang.modules.bxlens.util.Plain
				    .parse( java.nio.file.Files.readString( f, java.nio.charset.StandardCharsets.UTF_8 ) ) ), System.currentTimeMillis() - hours * 3_600_000L );
			}
		} catch ( Throwable t ) {
			getLogger().warn( "bx-lens: could not read the saved reports and errors from [{}]: {}", storeDir, t.toString() );
		}
	}

	/**
	 * Save reports and errors when the disk store is on and something changed. Old entries are dropped to respect the retention and size
	 * limits. Never throws.
	 */
	public void flushStore( boolean force ) {
		try {
			if ( !diskStoreOn() ) {
				return;
			}
			int		hours		= Math.max( 1, config.getInt( "store.retentionHours", 72 ) );
			long	maxBytes	= Math.max( 1, config.getInt( "store.maxMB", 50 ) ) * 1024L * 1024L;
			errors.prune( System.currentTimeMillis() - hours * 3_600_000L );
			if ( force || reports.isDirty() ) {
				ortus.boxlang.modules.bxlens.util.Plain.writeAtomic( storeDir.resolve( "reports.json" ), Json.write( reports.toPersist() ) );
			}
			if ( force || errors.isDirty() ) {
				List<Map<String, Object>>	all		= errors.toPersist();
				String						json	= Json.write( ErrorStore.fileOf( identity.get().toMap(), all ) );
				while ( json.length() > maxBytes && all.size() > 1 ) {
					all.sort( ( a, b ) -> Long.compare( ortus.boxlang.modules.bxlens.util.Plain.num( a.get( "lastSeen" ), 0 ),
					    ortus.boxlang.modules.bxlens.util.Plain.num( b.get( "lastSeen" ), 0 ) ) );
					all		= new ArrayList<>( all.subList( all.size() / 2, all.size() ) );
					json	= Json.write( ErrorStore.fileOf( identity.get().toMap(), all ) );
				}
				ortus.boxlang.modules.bxlens.util.Plain.writeAtomic( storeDir.resolve( "errors.json" ), json );
			}
		} catch ( Throwable t ) {
			getLogger().warn( "bx-lens: could not save reports and errors: {}", t.toString() );
		}
	}

	public QueryStats getQueryStats() {
		return queryStats;
	}

	/**
	 * The requests running right now.
	 */
	public List<Map<String, Object>> inflight() {
		List<Map<String, Object>>	out	= new ArrayList<>();
		long						now	= System.nanoTime();
		for ( LensRequest r : active.values() ) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put( "id", r.id );
			m.put( "method", r.method );
			m.put( "uri", r.uri );
			m.put( "queryString", ortus.boxlang.modules.bxlens.util.Secrets.redactQuery( r.queryString, config, 500 ) );
			m.put( "app", r.appName );
			m.put( "remoteAddr", r.remoteAddr );
			m.put( "serverHost", r.serverHost );
			m.put( "serverIp", r.serverIp );
			m.put( "serverId", r.serverId );
			m.put( "startedAt", r.startMillis );
			m.put( "elapsedMs", ( now - r.startNanos ) / 1_000_000L );
			Thread t = r.thread;
			m.put( "thread", t == null ? "" : t.getName() );
			m.put( "threadState", t == null ? "" : t.getState().name() );
			m.put( "queries", r.queries.size() );
			out.add( m );
		}
		out.sort( ( a, b ) -> Long.compare( ( Long ) b.get( "elapsedMs" ), ( Long ) a.get( "elapsedMs" ) ) );
		return out;
	}

	/**
	 * What a running request is doing right now: the stack of its thread.
	 */
	public Map<String, Object> inflightStack( String id ) {
		LensRequest r = active.get( id );
		if ( r == null || r.thread == null ) {
			return null;
		}
		Map<String, Object>			m		= new LinkedHashMap<>();
		List<Map<String, Object>>	frames	= new ArrayList<>();
		for ( StackTraceElement e : r.thread.getStackTrace() ) {
			if ( frames.size() >= 80 ) {
				break;
			}
			Map<String, Object> f = new LinkedHashMap<>();
			f.put( "text", e.toString() );
			String file = e.getFileName() == null ? "" : e.getFileName().toLowerCase();
			f.put( "bx", file.endsWith( ".bx" ) || file.endsWith( ".bxm" ) || file.endsWith( ".bxs" ) || file.endsWith( ".cfc" ) || file.endsWith( ".cfm" ) );
			frames.add( f );
		}
		m.put( "id", id );
		m.put( "thread", r.thread.getName() );
		m.put( "state", r.thread.getState().name() );
		m.put( "frames", frames );
		return m;
	}

	/**
	 * A file of the bar (styles, script, markup, icons, Alpine), or null.
	 */
	public BarRenderer.Asset getBarAsset( String name ) {
		BarRenderer r = renderer;
		return r == null ? null : r.asset( name );
	}

	/**
	 * May the browser keep this file for a year? True when the URL carries the current hash of the file.
	 */
	public boolean barAssetImmutable( String name, String version ) {
		BarRenderer r = renderer;
		return r != null && r.immutable( name, version );
	}

	public RuntimeInfo getRuntimeInfo() {
		return runtimeInfo;
	}

	public OrmData getOrm() {
		return orm;
	}

	public EnvironmentData getEnvironment() {
		return environment;
	}

	public LogData getLogs() {
		return logs;
	}

	public CacheData getCaches() {
		return caches;
	}

	public DatasourceData getDatasources() {
		return datasources;
	}

	public HeapDumper getHeapDumper() {
		return heapDumper;
	}

	public Audit getAudit() {
		return audit;
	}

	public BoxLangLogger getLogger() {
		BoxLangLogger l = logger;
		if ( l == null ) {
			l		= ( runtime != null ? runtime : BoxRuntime.getInstance() ).getLoggingService().getLogger( "bxLens" );
			logger	= l;
		}
		return l;
	}

}

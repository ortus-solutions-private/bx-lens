/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import ortus.boxlang.modules.bxlens.SettingsStore;
import ortus.boxlang.modules.bxlens.util.Plain;

/**
 * The MCP servers Lensy may ask, as an admin set them up in the console. It keeps the list (saved in the settings overrides file, under
 * <code>mcp.servers</code>), what was found on each server (its tools, kept five minutes), and makes the calls. It makes no decision about
 * who may call what: that is the {@link Toolbox}, which every call passes through.
 */
public final class McpService {

	/** How long the tools of a server are kept before they are asked for again. */
	public static final long	TTL_MS				= 5 * 60_000L;
	/** Seconds the list of tools of a server may take. */
	public static final int		DISCOVERY_SECONDS	= 10;
	/** Seconds one tool call may take. */
	public static final int		CALL_SECONDS		= 20;
	/** Custom servers that may be added. */
	public static final int		MAX_CUSTOM			= 20;
	/** Connection tests one console session may start in a minute. */
	public static final int		TESTS_PER_MINUTE	= 10;

	/** A tool of a server as Lensy sees it. {@code wire} is the name the model uses, {@code display} the one people read. */
	public record Tool( String serverId, String serverName, String tool, String wire, String display, String description, Map<String, Object> schema,
	    boolean builtin, boolean needsApproval, String url ) {
	}

	/** What the last look at a server found. */
	public record Discovery( String status, String reason, List<McpClient.ToolInfo> tools, long at ) {
	}

	private final SettingsStore							store;
	private final java.util.function.Supplier<String>	builtinBase;
	private final McpClient								client;
	private final LongSupplier							clock;
	private final Map<String, McpServer>				servers		= new java.util.LinkedHashMap<>();
	private final Map<String, Discovery>				found		= new ConcurrentHashMap<>();
	private final Map<String, ArrayDeque<Long>>			tests		= new ConcurrentHashMap<>();
	private final List<String>							problems	= new ArrayList<>();
	private final McpUrls.Resolver						resolver;
	private volatile ExecutorService					pool;

	/**
	 * @param store       where the list is saved
	 * @param builtinBase empty for the real addresses of the builtin servers; else the address of a stand-in that serves each at base/id
	 * @param resolver    resolves host names (replaced in tests)
	 * @param client      the MCP client: bx-ai in the module, a fake server client in tests
	 */
	public McpService( SettingsStore store, java.util.function.Supplier<String> builtinBase, McpUrls.Resolver resolver, LongSupplier clock,
	    McpClient client ) {
		this.store			= store;
		this.builtinBase	= builtinBase;
		this.resolver		= resolver == null ? McpUrls.SYSTEM : resolver;
		this.client			= client;
		this.clock			= clock == null ? System::currentTimeMillis : clock;
		load();
	}

	/**
	 * @param invocationPath the class path of this module (where the bridge to the MCP client of bx-ai is), known once the module has loaded
	 */
	public McpService( SettingsStore store, java.util.function.Supplier<String> builtinBase, java.util.function.Supplier<String> invocationPath ) {
		this( store, builtinBase, null, null, new BxAiMcpClient( null, invocationPath ) );
	}

	// ---------------------------------------------------------------------------------------------
	// The list
	// ---------------------------------------------------------------------------------------------

	private synchronized void load() {
		this.servers.clear();
		this.problems.clear();
		for ( McpServer d : McpDefaults.servers( this.builtinBase.get() ) ) {
			this.servers.put( d.id(), d );
		}
		int custom = 0;
		for ( Object raw : this.store.mcpServers() ) {
			try {
				McpServer	s	= McpServer.fromStored( raw );
				McpServer	d	= this.servers.get( s.id() );
				if ( s.builtin() ) {
					if ( d == null || !d.builtin() ) {
						throw new IllegalArgumentException( "The id [" + s.id() + "] is not a builtin server." );
					}
					// Only the switches of a builtin server are kept. Its address is not editable.
					this.servers.put( d.id(), d.with( s.enabled(), false, s.allowedTools().isEmpty() ? List.of( McpServer.ALL ) : s.allowedTools() ) );
					continue;
				}
				if ( d != null ) {
					throw new IllegalArgumentException( "The id [" + s.id() + "] is taken by a builtin server." );
				}
				if ( custom >= MAX_CUSTOM ) {
					throw new IllegalArgumentException( "More than " + MAX_CUSTOM + " custom servers." );
				}
				custom++;
				this.servers.put( s.id(), s );
			} catch ( RuntimeException e ) {
				this.problems.add( "Skipped a saved server: " + e.getMessage() );
			}
		}
	}

	/** Problems found when the saved list was read. A bad entry is skipped and listed here; startup is never blocked. */
	public synchronized List<String> problems() {
		return List.copyOf( this.problems );
	}

	public synchronized List<McpServer> list() {
		return List.copyOf( this.servers.values() );
	}

	public synchronized McpServer get( String id ) {
		return id == null ? null : this.servers.get( id );
	}

	private void save() throws IOException {
		List<Map<String, Object>> out = new ArrayList<>();
		for ( McpServer s : this.servers.values() ) {
			// A builtin server that was never touched is not saved
			if ( s.builtin() && !s.enabled() && s.allowsAll() ) {
				continue;
			}
			out.add( s.toStored() );
		}
		this.store.setMcpServers( out );
	}

	/**
	 * Add a custom server (disabled, no tools allowed). The address is checked, and resolved.
	 *
	 * @throws IllegalArgumentException when the name or the address is not valid
	 */
	public synchronized McpServer add( String name, String url ) throws IOException {
		String	n		= McpServer.cleanName( name );
		String	u		= McpUrls.check( url, this.resolver );
		long	count	= this.servers.values().stream().filter( s -> !s.builtin() ).count();
		if ( count >= MAX_CUSTOM ) {
			throw new IllegalArgumentException( "At most " + MAX_CUSTOM + " custom servers can be added." );
		}
		for ( McpServer s : this.servers.values() ) {
			if ( s.name().equalsIgnoreCase( n ) ) {
				throw new IllegalArgumentException( "A server with that name exists." );
			}
		}
		String	base	= McpServer.slug( n );
		String	id		= base;
		int		i		= 2;
		while ( this.servers.containsKey( id ) || McpDefaults.isBuiltinId( id ) ) {
			id = base + "-" + i++;
		}
		McpServer s = new McpServer( id, n, u, false, false, false, List.of() );
		this.servers.put( id, s );
		try {
			save();
		} catch ( IOException e ) {
			this.servers.remove( id );
			throw e;
		}
		return s;
	}

	public synchronized void remove( String id ) throws IOException {
		McpServer s = this.servers.get( id );
		if ( s == null ) {
			throw new IllegalArgumentException( "There is no such server." );
		}
		if ( s.builtin() ) {
			throw new IllegalArgumentException( "A builtin server cannot be removed. Turn it off instead." );
		}
		this.servers.remove( id );
		try {
			save();
		} catch ( IOException e ) {
			this.servers.put( id, s );
			throw e;
		}
		this.found.remove( id );
	}

	/**
	 * Turn a server on or off. Turning one on checks its address again, and looks for its tools.
	 */
	public synchronized McpServer enable( String id, boolean on ) throws IOException {
		McpServer s = require( id );
		if ( on ) {
			McpUrls.check( s.url(), this.resolver );
		}
		return replace( s, s.with( on, s.trusted(), s.allowedTools() ) );
	}

	/**
	 * Change the allowed tools and the trusted flag of a server. Tool names must be ones the server offers (look first with a test).
	 *
	 * @param allowed tool names, or a list holding only <code>*</code> for all of them
	 * 
	 * @throws IllegalArgumentException when a tool is unknown or trusted is asked of a builtin server
	 */
	public synchronized McpServer update( String id, Boolean trusted, List<String> allowed ) throws IOException {
		McpServer	s	= require( id );
		boolean		t	= s.trusted();
		if ( trusted != null ) {
			if ( s.builtin() && trusted ) {
				throw new IllegalArgumentException( "A builtin server needs no trust: its tools run without a click." );
			}
			t = trusted;
		}
		List<String> a = s.allowedTools();
		if ( allowed != null ) {
			if ( allowed.size() > McpClient.MAX_TOOLS ) {
				throw new IllegalArgumentException( "Too many tools." );
			}
			if ( allowed.contains( McpServer.ALL ) ) {
				a = List.of( McpServer.ALL );
			} else {
				Discovery		d		= this.found.get( id );
				List<String>	known	= new ArrayList<>();
				if ( d != null ) {
					d.tools().forEach( x -> known.add( x.name() ) );
				}
				for ( String x : allowed ) {
					if ( !known.contains( x ) ) {
						throw new IllegalArgumentException( "The server has no tool called [" + ( x.length() > 60 ? x.substring( 0, 60 ) : x )
						    + "]. Press Test to look for its tools first." );
					}
				}
				a = List.copyOf( new java.util.LinkedHashSet<>( allowed ) );
			}
		}
		return replace( s, s.with( s.enabled(), t, a ) );
	}

	private McpServer require( String id ) {
		McpServer s = this.servers.get( id );
		if ( s == null ) {
			throw new IllegalArgumentException( "There is no such server." );
		}
		return s;
	}

	private McpServer replace( McpServer old, McpServer next ) throws IOException {
		this.servers.put( old.id(), next );
		try {
			save();
		} catch ( IOException e ) {
			this.servers.put( old.id(), old );
			throw e;
		}
		return next;
	}

	/** May this console session start another connection test? At most {@link #TESTS_PER_MINUTE} a minute. */
	public boolean testAllowed( String sessionId ) {
		long				now	= this.clock.getAsLong();
		ArrayDeque<Long>	q	= this.tests.computeIfAbsent( sessionId == null ? "" : sessionId, k -> new ArrayDeque<>() );
		synchronized ( q ) {
			while ( !q.isEmpty() && now - q.peekFirst() > 60_000L ) {
				q.pollFirst();
			}
			if ( q.size() >= TESTS_PER_MINUTE ) {
				return false;
			}
			q.addLast( now );
			if ( this.tests.size() > 500 ) {
				this.tests.keySet().removeIf( k -> this.tests.get( k ).isEmpty() );
			}
			return true;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// Finding tools
	// ---------------------------------------------------------------------------------------------

	/**
	 * Look at a server now: connect, list its tools, remember the result. Never throws; a failure becomes the status of the server.
	 */
	public Discovery discover( String id ) {
		McpServer s = get( id );
		if ( s == null ) {
			return new Discovery( "unreachable", "There is no such server.", List.of(), this.clock.getAsLong() );
		}
		Discovery d;
		try {
			List<McpClient.ToolInfo> tools = this.client.listTools( s.url(), Duration.ofSeconds( DISCOVERY_SECONDS ) );
			d = new Discovery( "ok", "", tools, this.clock.getAsLong() );
		} catch ( McpClient.McpException e ) {
			d = new Discovery( e.kind, e.getMessage(), List.of(), this.clock.getAsLong() );
		} catch ( RuntimeException e ) {
			d = new Discovery( "unreachable", "unexpected answer (" + e.getClass().getSimpleName() + ")", List.of(), this.clock.getAsLong() );
		}
		this.found.put( id, d );
		return d;
	}

	/**
	 * Before a chat: look again at every enabled server whose tools are older than five minutes (or unknown), at the same time, and wait for
	 * them at most {@link #DISCOVERY_SECONDS} seconds. A server that does not answer is marked and the chat goes on without it.
	 */
	public void refreshStale() {
		long			now		= this.clock.getAsLong();
		List<Future<?>>	running	= new ArrayList<>();
		for ( McpServer s : list() ) {
			Discovery d = this.found.get( s.id() );
			if ( s.enabled() && ( d == null || now - d.at() > TTL_MS ) ) {
				running.add( pool().submit( () -> discover( s.id() ) ) );
			}
		}
		long until = System.currentTimeMillis() + ( DISCOVERY_SECONDS + 2 ) * 1000L;
		for ( Future<?> f : running ) {
			try {
				f.get( Math.max( 1, until - System.currentTimeMillis() ), TimeUnit.MILLISECONDS );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				return;
			} catch ( Exception e ) {
				// Marked by discover or still running; the chat goes on
			}
		}
	}

	/** Forget what was found (tests, and a changed server list). */
	public void forget() {
		this.found.clear();
	}

	private synchronized ExecutorService pool() {
		if ( this.pool == null ) {
			this.pool = Executors.newFixedThreadPool( 6, r -> {
				Thread t = new Thread( r, "bxlens-mcp" );
				t.setDaemon( true );
				return t;
			} );
		}
		return this.pool;
	}

	public synchronized void shutdown() {
		if ( this.pool != null ) {
			this.pool.shutdownNow();
			this.pool = null;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// What Lensy sees
	// ---------------------------------------------------------------------------------------------

	/**
	 * The tools Lensy may offer the model right now, from what was found (no network). A tool is listed when its server is enabled, the
	 * server answered, and the tool is on the allowed list. A viewer gets the tools of builtin servers only.
	 */
	public List<Tool> tools( boolean admin ) {
		List<Tool>		out		= new ArrayList<>();
		List<String>	wires	= new ArrayList<>();
		for ( McpServer s : list() ) {
			Discovery d = this.found.get( s.id() );
			if ( !s.enabled() || d == null || !"ok".equals( d.status() ) || !admin && !s.builtin() ) {
				continue;
			}
			for ( McpClient.ToolInfo t : d.tools() ) {
				if ( !s.allows( t.name() ) ) {
					continue;
				}
				String wire = wireName( s.id(), t.name() );
				if ( wires.contains( wire ) ) {
					continue;
				}
				wires.add( wire );
				String desc = t.description().length() > 500 ? t.description().substring( 0, 500 ) + "..." : t.description();
				out.add( new Tool( s.id(), s.name(), t.name(), wire, s.id() + "." + t.name(), "[" + s.name() + "] " + desc, cleanSchema( t.schema() ),
				    s.builtin(), !s.builtin() && !s.trusted() || s.isWrite( t.name() ), s.url() ) );
			}
		}
		return out;
	}

	/** The tool the model named, among those this user may use; null when there is none. */
	public Tool find( String wire, boolean admin ) {
		for ( Tool t : tools( admin ) ) {
			if ( t.wire().equals( wire ) || t.display().equals( wire ) ) {
				return t;
			}
		}
		return null;
	}

	/** A name the model providers accept: letters, digits, underscore and hyphen, at most 64 characters. */
	public static String wireName( String serverId, String tool ) {
		StringBuilder sb = new StringBuilder( serverId ).append( "__" );
		for ( char c : tool.toCharArray() ) {
			sb.append( c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' ? c : '_' );
		}
		return sb.length() > 64 ? sb.substring( 0, 64 ) : sb.toString();
	}

	/** A string that changes when the tools offered to the model change. */
	public String signature( boolean admin ) {
		StringBuilder sb = new StringBuilder();
		for ( Tool t : tools( admin ) ) {
			sb.append( t.wire() ).append( ':' ).append( t.description().hashCode() ).append( ':' ).append( t.schema().hashCode() ).append( ';' );
		}
		return sb.toString();
	}

	/**
	 * Keep of a tool schema what is safe to hand to a model and to validate against: object properties with a simple type and a short
	 * description, and the names that are required. Everything else a server may put there is dropped.
	 */
	static Map<String, Object> cleanSchema( Map<String, Object> raw ) {
		Map<String, Object>	props	= new LinkedHashMap<>();
		List<Object>		req		= new ArrayList<>();
		Map<String, Object>	in		= Plain.map( raw.get( "properties" ) );
		for ( Map.Entry<String, Object> e : in.entrySet() ) {
			if ( props.size() >= 30 || e.getKey().isEmpty() || e.getKey().length() > 60 || e.getKey().startsWith( "_" ) ) {
				continue;
			}
			Map<String, Object>	p		= Plain.map( e.getValue() );
			String				type	= Plain.str( p.get( "type" ) ).toLowerCase( Locale.ROOT );
			if ( !List.of( "string", "number", "integer", "boolean" ).contains( type ) ) {
				type = "string";
			}
			String				desc	= Plain.str( p.get( "description" ) );
			Map<String, Object>	o		= new LinkedHashMap<>();
			o.put( "type", type );
			o.put( "description", desc.length() > 300 ? desc.substring( 0, 300 ) + "..." : desc );
			props.put( e.getKey(), o );
		}
		for ( Object r : Plain.list( raw.get( "required" ) ) ) {
			if ( props.containsKey( String.valueOf( r ) ) ) {
				req.add( String.valueOf( r ) );
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put( "properties", props );
		out.put( "required", req );
		return out;
	}

	// ---------------------------------------------------------------------------------------------
	// Calling
	// ---------------------------------------------------------------------------------------------

	/**
	 * Call a tool. The address is checked again first. Used by the {@link Toolbox} after it has applied every rule.
	 *
	 * @throws McpClient.McpException with a reason that is safe to show
	 */
	McpClient.CallResult call( Tool t, Map<String, Object> args ) throws McpClient.McpException {
		McpServer s = get( t.serverId() );
		if ( s == null || !s.enabled() ) {
			throw new McpClient.McpException( "refused", "the server is off" );
		}
		return this.client.callTool( s.url(), t.tool(), args, Duration.ofSeconds( CALL_SECONDS ) );
	}

	// ---------------------------------------------------------------------------------------------
	// The page
	// ---------------------------------------------------------------------------------------------

	/** The list as the console shows it. */
	public Map<String, Object> view( boolean canChange ) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for ( McpServer s : list() ) {
			Discovery			d	= this.found.get( s.id() );
			Map<String, Object>	m	= new LinkedHashMap<>();
			m.put( "id", s.id() );
			m.put( "name", s.name() );
			m.put( "url", s.url() );
			m.put( "builtin", s.builtin() );
			m.put( "enabled", s.enabled() );
			m.put( "trusted", s.trusted() );
			m.put( "allowAll", s.allowsAll() );
			m.put( "allowedTools", s.allowedTools() );
			m.put( "status", d == null ? ( s.enabled() ? "pending" : "disabled" ) : d.status() );
			m.put( "reason", d == null ? "" : d.reason() );
			m.put( "checkedAt", d == null ? 0 : d.at() );
			List<Map<String, Object>> tools = new ArrayList<>();
			if ( d != null ) {
				for ( McpClient.ToolInfo t : d.tools() ) {
					Map<String, Object> tm = new LinkedHashMap<>();
					tm.put( "name", t.name() );
					tm.put( "description", t.description().length() > 200 ? t.description().substring( 0, 200 ) + "..." : t.description() );
					tm.put( "allowed", s.allows( t.name() ) );
					tm.put( "defaultOn", !s.isWrite( t.name() ) );
					tm.put( "writes", s.isWrite( t.name() ) );
					tools.add( tm );
				}
			}
			m.put( "tools", tools );
			m.put( "toolCount", tools.size() );
			rows.add( m );
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put( "servers", rows );
		out.put( "problems", problems() );
		out.put( "canChange", canChange );
		out.put( "maxCustom", MAX_CUSTOM );
		return out;
	}

}

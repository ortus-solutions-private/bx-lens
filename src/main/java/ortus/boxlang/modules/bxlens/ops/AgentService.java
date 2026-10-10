/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ortus.boxlang.modules.bxlens.ConsoleAuth;
import ortus.boxlang.modules.bxlens.LensConfig;
import ortus.boxlang.modules.bxlens.LensService;
import ortus.boxlang.modules.bxlens.util.Plain;
import ortus.boxlang.modules.bxlens.util.Secrets;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.interop.DynamicObject;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * Lensy of a server. It owns one conversation per console session (memory in the JVM, gone at logout, expiry or reset), the bounded
 * number of chats that may run at once, their deadlines, and the index of the documentation.
 * <p>
 * The agent itself is the BoxLang class <code>models/ops/Lensy.bx</code>, built on bx-ai. Everything it can do goes through the
 * {@link Toolbox} of the session, which applies the rules of the console to the user who is logged in.
 */
public final class AgentService {

	/** Longest question, in characters. */
	public static final int	MAX_MESSAGE			= 2000;
	/** Chats one session may start in a minute. */
	public static final int	CHATS_PER_MINUTE	= 20;
	/** Conversations kept at once. The oldest idle one goes when more are needed. */
	public static final int	MAX_CONVERSATIONS	= 200;

	/**
	 * A chat cannot start. The status is the HTTP status for the browser.
	 */
	public static final class Refusal extends RuntimeException {

		private static final long	serialVersionUID	= 1L;
		public final int			status;
		public final boolean		plus;

		Refusal( int status, String message, boolean plus ) {
			super( message );
			this.status	= status;
			this.plus	= plus;
		}
	}

	/** One browser session's conversation. */
	static final class Conversation {

		final String			sessionId;
		final String			role;
		final Toolbox			toolbox;
		volatile DynamicObject	agent;
		volatile String			configKey	= "";
		volatile ChatTurn		active;
		volatile long			lastUsed	= System.currentTimeMillis();
		final ArrayDeque<Long>	starts		= new ArrayDeque<>();

		Conversation( String sessionId, String role, Toolbox toolbox ) {
			this.sessionId	= sessionId;
			this.role		= role;
			this.toolbox	= toolbox;
		}
	}

	private final LensService					service;
	private final Approvals						approvals		= new Approvals();
	private final Map<String, Conversation>		conversations	= new ConcurrentHashMap<>();
	private final AtomicInteger					running			= new AtomicInteger();
	private final AtomicInteger					threadSeq		= new AtomicInteger();
	private final DocsIndex						docs			= new DocsIndex();
	private volatile ExecutorService			pool;
	private volatile ScheduledExecutorService	watchdog;
	private volatile DynamicObject				rag;
	private volatile String						invocationPath	= "";

	public AgentService( LensService service ) {
		this.service = service;
	}

	public Approvals approvals() {
		return this.approvals;
	}

	public DocsIndex docs() {
		return this.docs;
	}

	/** The class path of this module, set when the module loads. */
	public void invocationPath( String path ) {
		this.invocationPath = path == null ? "" : path;
	}

	/** The class path of this module, empty until the module has loaded. */
	public String invocationPath() {
		return this.invocationPath;
	}

	// ---------------------------------------------------------------------------------------------
	// Status
	// ---------------------------------------------------------------------------------------------

	/**
	 * What the page needs to know to show the launcher and the chat.
	 */
	public Map<String, Object> status( ConsoleAuth.Session s ) {
		LensConfig			cfg			= this.service.getConfig();
		boolean				installed	= this.service.getAi().installed();
		boolean				enabled		= cfg.getBool( "ai.enabled", false );
		boolean				licensed	= this.service.getLicensing().has( "ai" );
		Toolbox				tb			= s == null ? null : toolbox( s );
		Map<String, Object>	m			= new LinkedHashMap<>();
		m.put( "installed", installed );
		m.put( "enabled", enabled );
		m.put( "licensed", licensed );
		m.put( "available", installed && enabled && licensed );
		m.put( "reason",
		    installed ? !licensed ? "Lensy is a BoxLang+ feature. A license or trial is needed." : !enabled ? "Lensy is off (ai.enabled)." : ""
		        : "The bx-ai module is not installed." );
		m.put( "provider", this.service.getAi().provider() );
		m.put( "model", this.service.getAi().model() );
		m.put( "tools", tb == null ? 0 : tb.toolNames().size() + tb.mcpTools().size() );
		m.put( "role", s == null ? "" : s.role );
		m.put( "actions", tb != null && tb.toolNames().contains( "runGc" ) );
		Map<String, Object>	r		= new LinkedHashMap<>();
		boolean				ragOn	= cfg.getBool( "ai.rag", true );
		r.put( "enabled", ragOn );
		r.put( "mode", !ragOn ? "off" : this.docs.mode() );
		r.put( "chunks", this.docs.size() );
		r.put( "note", this.docs.note() );
		m.put( "rag", r );
		m.put( "maxToolCalls", cfg.getInt( "ai.maxToolCalls", 8 ) );
		m.put( "busy", this.running.get() );
		m.put( "maxConcurrentChats", maxChats() );
		Conversation c = s == null ? null : this.conversations.get( s.id );
		m.put( "chatting", c != null && c.active != null && !c.active.finished() );
		m.put( "pendingApprovals", s == null ? 0 : this.approvals.pendingCount( s.id ) );
		return m;
	}

	private int maxChats() {
		return Math.max( 1, Math.min( 20, this.service.getConfig().getInt( "ai.maxConcurrentChats", 3 ) ) );
	}

	private Toolbox toolbox( ConsoleAuth.Session s ) {
		Conversation c = this.conversations.get( s.id );
		return c != null ? c.toolbox : new Toolbox( this.service, s.role, s.id, s.remoteAddr, this.approvals );
	}

	// ---------------------------------------------------------------------------------------------
	// Chat
	// ---------------------------------------------------------------------------------------------

	/**
	 * Start answering a question. The answer arrives as events on the returned turn.
	 *
	 * @throws Refusal when the chat cannot start
	 */
	public ChatTurn chat( ConsoleAuth.Session s, String ip, String message ) {
		LensConfig cfg = this.service.getConfig();
		if ( message == null || message.isBlank() || message.length() > MAX_MESSAGE ) {
			throw new Refusal( 400, "Ask a question of up to " + MAX_MESSAGE + " characters.", false );
		}
		if ( !this.service.getLicensing().has( "ai" ) ) {
			throw new Refusal( 409, "Lensy is a BoxLang+ feature. A license or trial is needed.", true );
		}
		if ( !this.service.getAi().installed() ) {
			throw new Refusal( 409, "The bx-ai module is not installed.", false );
		}
		if ( !cfg.getBool( "ai.enabled", false ) ) {
			throw new Refusal( 409, "Lensy is off. Turn on ai.enabled.", false );
		}
		Conversation c = conversation( s, ip );
		synchronized ( c ) {
			if ( c.active != null && !c.active.finished() ) {
				throw new Refusal( 409, "An answer is still being written. Wait for it, or reset the conversation.", false );
			}
			long now = System.currentTimeMillis();
			while ( !c.starts.isEmpty() && now - c.starts.peekFirst() > 60_000L ) {
				c.starts.pollFirst();
			}
			if ( c.starts.size() >= CHATS_PER_MINUTE ) {
				throw new Refusal( 429, "Too many questions in a minute. Wait a little.", false );
			}
			int max = maxChats();
			if ( this.running.incrementAndGet() > max ) {
				this.running.decrementAndGet();
				throw new Refusal( 429, "The assistant is busy with other chats. Try again in a moment.", false );
			}
			c.starts.addLast( now );
			ChatTurn turn = new ChatTurn( Long.toString( now, 36 ), Math.max( 10, cfg.getInt( "ai.timeoutSeconds", 120 ) ) * 1000L );
			c.active	= turn;
			c.lastUsed	= now;
			ensureWatchdog();
			try {
				turn.future( pool().submit( () -> run( c, turn, message.trim() ) ) );
			} catch ( RuntimeException e ) {
				this.running.decrementAndGet();
				c.active = null;
				throw new Refusal( 429, "The assistant is busy with other chats. Try again in a moment.", false );
			}
			return turn;
		}
	}

	private void run( Conversation c, ChatTurn turn, String message ) {
		try {
			c.toolbox.beginTurn( turn );
			// The tools of the MCP servers that are on: look again at the ones not looked at for five minutes. A server that does not answer is
			// marked, and the answer goes on without it.
			if ( c.toolbox.licensed() ) {
				this.service.getMcp().refreshStale();
			}
			Object agent = agentFor( c );
			if ( turn.cancelled() ) {
				return;
			}
			IBoxContext ctx = context();
			callMethod( ctx, ( DynamicObject ) agent, "chat", message, turn );
			turn.done();
		} catch ( Throwable t ) {
			if ( turn.cancelled() || Thread.currentThread().isInterrupted() ) {
				turn.done();
			} else {
				Throwable root = t;
				while ( root.getCause() != null && root.getCause() != root ) {
					root = root.getCause();
				}
				this.service.getLogger().warn( "bx-lens Lensy failed: {}", root.toString() );
				turn.error( friendly( root ) );
			}
		} finally {
			c.toolbox.endTurn();
			this.running.decrementAndGet();
			c.lastUsed = System.currentTimeMillis();
		}
	}

	/**
	 * A message for the browser from an exception: what happened, without a stack and without a secret.
	 */
	static String friendly( Throwable root ) {
		String msg = Secrets.text( String.valueOf( root.getMessage() ) );
		if ( msg.length() > 300 ) {
			msg = msg.substring( 0, 300 ) + "...";
		}
		String type = root.getClass().getSimpleName() + " " + msg;
		if ( type.contains( "MaxInteractions" ) ) {
			return "The assistant needed more steps than allowed (ai.maxToolCalls). Ask a narrower question.";
		}
		return "The assistant failed: " + msg;
	}

	private Object agentFor( Conversation c ) {
		List<McpService.Tool>	mcp	= c.toolbox.mcpTools();
		String					key	= configKey() + "|" + this.service.getMcp().signature( c.toolbox.isAdmin() );
		synchronized ( c ) {
			if ( c.agent == null || !key.equals( c.configKey ) ) {
				IBoxContext	ctx		= context();
				// An agent rebuilt because the tools changed keeps the conversation: the new one gets the memory of the old one. A changed model
				// or setting starts afresh, as before.
				Object		memory	= "";
				if ( c.agent != null && c.configKey.startsWith( configKey() + "|" ) ) {
					memory = callMethod( ctx, c.agent, "getMemory" );
				}
				c.agent		= instantiate( ctx, "models.ops.Lensy", c.toolbox, settingsStruct(), c.toolbox.toolNames(), mcpSpecs( mcp ), memory );
				c.configKey	= key;
			}
			return c.agent;
		}
	}

	/** The MCP tools as BoxLang values: name (as the model uses it), description and the cleaned schema. */
	private static ortus.boxlang.runtime.types.Array mcpSpecs( List<McpService.Tool> tools ) {
		ortus.boxlang.runtime.types.Array out = new ortus.boxlang.runtime.types.Array();
		for ( McpService.Tool t : tools ) {
			IStruct spec = new Struct();
			spec.put( Key.of( "name" ), t.wire() );
			spec.put( Key.of( "description" ), t.description() );
			spec.put( Key.of( "schema" ), bx( t.schema() ) );
			out.add( spec );
		}
		return out;
	}

	/** Maps become structs and lists become arrays, all the way down. */
	private static Object bx( Object v ) {
		if ( v instanceof Map<?, ?> m ) {
			IStruct s = new Struct();
			for ( Map.Entry<?, ?> e : m.entrySet() ) {
				s.put( Key.of( String.valueOf( e.getKey() ) ), bx( e.getValue() ) );
			}
			return s;
		}
		if ( v instanceof List<?> l ) {
			ortus.boxlang.runtime.types.Array a = new ortus.boxlang.runtime.types.Array();
			for ( Object o : l ) {
				a.add( bx( o ) );
			}
			return a;
		}
		return v;
	}

	/** A string that changes when a setting the agent was built from changes. */
	private String configKey() {
		LensConfig	cfg	= this.service.getConfig();
		var			ai	= this.service.getAi();
		return String.join( "|", ai.provider(), ai.model(), ai.baseUrl(), String.valueOf( cfg.getString( "ai.temperature", "0.2" ) ),
		    String.valueOf( cfg.getInt( "ai.memoryMessages", 20 ) ), String.valueOf( cfg.getInt( "ai.maxToolCalls", 8 ) ),
		    String.valueOf( cfg.getInt( "ai.timeoutSeconds", 120 ) ), cfg.getString( "ai.apiKeyEnv", "" ), String.valueOf( ai.apiKey().hashCode() ),
		    String.valueOf( cfg.getBool( "ai.actions", true ) ), String.valueOf( cfg.getBool( "ai.rag", true ) ),
		    String.valueOf( cfg.getBool( "console.readOnly", false ) ), String.valueOf( cfg.getBool( "console.actions", true ) ) );
	}

	private IStruct settingsStruct() {
		LensConfig	cfg	= this.service.getConfig();
		var			ai	= this.service.getAi();
		IStruct		s	= new Struct();
		s.put( Key.of( "provider" ), ai.provider() );
		s.put( Key.of( "model" ), ai.model() );
		s.put( Key.of( "baseUrl" ), ai.baseUrl() );
		s.put( Key.of( "apiKey" ), ai.apiKey() );
		double temp = 0.2;
		try {
			temp = Double.parseDouble( cfg.getString( "ai.temperature", "0.2" ) );
		} catch ( NumberFormatException e ) {
			// Default
		}
		s.put( Key.of( "temperature" ), temp );
		s.put( Key.of( "timeoutSeconds" ), Math.max( 10, cfg.getInt( "ai.timeoutSeconds", 120 ) ) );
		s.put( Key.of( "maxToolCalls" ), Math.max( 1, cfg.getInt( "ai.maxToolCalls", 8 ) ) );
		s.put( Key.of( "memoryMessages" ), Math.max( 2, cfg.getInt( "ai.memoryMessages", 20 ) ) );
		s.put( Key.of( "server" ), this.service.getIdentity().get().host() + " (" + this.service.getIdentity().get().id() + ")" );
		return s;
	}

	private Conversation conversation( ConsoleAuth.Session s, String ip ) {
		Conversation c = this.conversations.get( s.id );
		if ( c == null ) {
			if ( this.conversations.size() >= MAX_CONVERSATIONS ) {
				this.conversations.values().stream().filter( x -> x.active == null ).min( java.util.Comparator.comparingLong( x -> x.lastUsed ) )
				    .ifPresent( x -> drop( x.sessionId ) );
			}
			c = this.conversations.computeIfAbsent( s.id,
			    k -> new Conversation( s.id, s.role, new Toolbox( this.service, s.role, s.id, ip, this.approvals ) ) );
		}
		return c;
	}

	/**
	 * Forget a conversation, cancel its turn and drop what it waits for. The memory is gone.
	 */
	public void reset( String sessionId ) {
		drop( sessionId );
	}

	/**
	 * The session ended (logout, expiry): the same as a reset.
	 */
	public void drop( String sessionId ) {
		Conversation c = this.conversations.remove( sessionId );
		this.approvals.cancelSession( sessionId );
		if ( c != null && c.active != null ) {
			c.active.cancel( "" );
		}
	}

	public int conversationCount() {
		return this.conversations.size();
	}

	/** Does this session have a conversation (tests)? */
	public boolean hasConversation( String sessionId ) {
		return this.conversations.containsKey( sessionId );
	}

	// ---------------------------------------------------------------------------------------------
	// Documentation search
	// ---------------------------------------------------------------------------------------------

	/**
	 * Search the bundled documentation. The index is built on first use and again only when the documents or the embedding settings change.
	 */
	public synchronized Map<String, Object> searchDocs( String question ) {
		Map<String, Object>	out	= new LinkedHashMap<>();
		LensConfig			cfg	= this.service.getConfig();
		Path				dir	= Path.of( this.service.getModuleDir() ).resolve( "assets" ).resolve( "docs" );
		if ( !Files.isDirectory( dir ) ) {
			out.put( "mode", "none" );
			out.put( "results", List.of() );
			out.put( "note", "The documentation is not bundled in this build." );
			return out;
		}
		String	sum	= DocsIndex.checksum( dir );
		String	sig	= this.service.getAi().provider() + "|" + this.service.getAi().baseUrl() + "|" + this.service.getAi().embeddingModel();
		if ( !this.docs.current( sum, sig ) ) {
			buildIndex( dir, sum, sig );
		}
		int limit = 4;
		if ( "embeddings".equals( this.docs.mode() ) && this.rag != null ) {
			try {
				Object						r		= callMethod( context(), this.rag, "search", question, limit );
				List<Map<String, Object>>	results	= new ArrayList<>();
				for ( Object o : Plain.list( Plain.plain( r ) ) ) {
					Map<String, Object> x = Plain.map( o );
					results.add(
					    DocsIndex.result( new DocsIndex.Chunk( Plain.str( x.get( "page" ) ), Plain.str( x.get( "heading" ) ), Plain.str( x.get( "text" ) ) ),
					        x.get( "score" ) instanceof Number n ? Math.round( n.doubleValue() * 1000.0 ) / 1000.0 : 0 ) );
				}
				out.put( "mode", "embeddings" );
				out.put( "results", results );
				return out;
			} catch ( Throwable t ) {
				this.docs.mode( "keywords", "The embedding model did not answer: " + friendly( rootOf( t ) ) );
			}
		}
		out.put( "mode", this.docs.mode() );
		out.put( "results", this.docs.keywordSearch( question, limit ) );
		if ( !this.docs.note().isEmpty() ) {
			out.put( "note", this.docs.note() );
		}
		return out;
	}

	private void buildIndex( Path dir, String sum, String sig ) {
		try {
			IBoxContext ctx = context();
			this.rag = instantiate( ctx, "models.ops.DocsRag", dir.toString(), embeddingSettings() );
			List<DocsIndex.Chunk>	chunks	= new ArrayList<>();
			Object					loaded	= callMethod( ctx, this.rag, "load" );
			for ( Object o : Plain.list( Plain.plain( loaded ) ) ) {
				Map<String, Object> m = Plain.map( o );
				chunks.add( new DocsIndex.Chunk( Plain.str( m.get( "page" ) ), Plain.str( m.get( "heading" ) ), Plain.str( m.get( "text" ) ) ) );
			}
			this.docs.set( chunks, sum, sig );
			if ( this.service.getConfig().getBool( "ai.rag", true ) && this.service.getAi().installed() && this.service.getLicensing().has( "ai" ) ) {
				try {
					callMethod( ctx, this.rag, "buildIndex" );
					this.docs.mode( "embeddings", "" );
				} catch ( Throwable t ) {
					this.docs.mode( "keywords", "No embedding model answered (" + friendly( rootOf( t ) ) + "). Searching by keywords." );
				}
			} else {
				this.docs.mode( "keywords", "" );
			}
		} catch ( Throwable t ) {
			this.service.getLogger().warn( "bx-lens could not load the documentation for the agent: {}", rootOf( t ).toString() );
			this.docs.set( List.of(), "", "" );
			this.docs.mode( "none", "The documentation could not be loaded." );
		}
	}

	private IStruct embeddingSettings() {
		IStruct s = new Struct();
		s.put( Key.of( "provider" ), this.service.getAi().provider() );
		s.put( Key.of( "embeddingModel" ), this.service.getAi().embeddingModel() );
		s.put( Key.of( "baseUrl" ), this.service.getAi().baseUrl() );
		s.put( Key.of( "apiKey" ), this.service.getAi().apiKey() );
		return s;
	}

	private static Throwable rootOf( Throwable t ) {
		Throwable root = t;
		while ( root.getCause() != null && root.getCause() != root ) {
			root = root.getCause();
		}
		return root;
	}

	// ---------------------------------------------------------------------------------------------
	// BoxLang
	// ---------------------------------------------------------------------------------------------

	private IBoxContext context() {
		BoxRuntime rt = BoxRuntime.getInstance();
		return new ScriptingRequestBoxContext( rt.getRuntimeContext() );
	}

	/**
	 * Call a method of a BoxLang class. A DynamicObject over a BoxLang class is called through its dereference, not as a Java method.
	 */
	private static Object callMethod( IBoxContext ctx, DynamicObject target, String name, Object... args ) {
		return target.dereferenceAndInvoke( ctx, Key.of( name ), args, false );
	}

	private DynamicObject instantiate( IBoxContext ctx, String path, Object... args ) {
		if ( this.invocationPath.isEmpty() ) {
			throw new IllegalStateException( "The module path is not known yet" );
		}
		DynamicObject cls = BoxRuntime.getInstance().getClassLocator().load( ctx, "bx:" + this.invocationPath + "." + path, List.of() );
		return cls.invokeConstructor( ctx, args );
	}

	// ---------------------------------------------------------------------------------------------
	// Threads
	// ---------------------------------------------------------------------------------------------

	private synchronized ExecutorService pool() {
		if ( this.pool == null ) {
			this.pool = Executors.newCachedThreadPool( r -> {
				Thread t = new Thread( r, "bxlens-agent-" + this.threadSeq.incrementAndGet() );
				t.setDaemon( true );
				return t;
			} );
		}
		return this.pool;
	}

	private synchronized void ensureWatchdog() {
		if ( this.watchdog == null ) {
			this.watchdog = Executors.newSingleThreadScheduledExecutor( r -> {
				Thread t = new Thread( r, "bxlens-agent-watchdog" );
				t.setDaemon( true );
				return t;
			} );
			this.watchdog.scheduleWithFixedDelay( this::sweep, 1, 1, TimeUnit.SECONDS );
		}
	}

	/**
	 * Cancel turns that ran out of time or whose session ended, and forget conversations of sessions that are gone.
	 */
	void sweep() {
		try {
			ConsoleAuth auth = this.service.getAuth();
			for ( Conversation c : this.conversations.values() ) {
				ChatTurn t = c.active;
				if ( t != null && !t.finished() && t.expired() ) {
					int secs = Math.max( 10, this.service.getConfig().getInt( "ai.timeoutSeconds", 120 ) );
					t.cancel( "The assistant took longer than " + secs + " seconds (ai.timeoutSeconds)." );
				}
				if ( auth.peek( c.sessionId ) == null ) {
					drop( c.sessionId );
				}
			}
		} catch ( Throwable t ) {
			// The watchdog never stops
		}
	}

	/**
	 * Stop everything: cancel the chats, forget the conversations. Called when the module stops.
	 */
	public void shutdown() {
		for ( Conversation c : this.conversations.values() ) {
			drop( c.sessionId );
		}
		this.conversations.clear();
		if ( this.watchdog != null ) {
			this.watchdog.shutdownNow();
			this.watchdog = null;
		}
		if ( this.pool != null ) {
			this.pool.shutdownNow();
			this.pool = null;
		}
		this.running.set( 0 );
		this.rag = null;
	}

}

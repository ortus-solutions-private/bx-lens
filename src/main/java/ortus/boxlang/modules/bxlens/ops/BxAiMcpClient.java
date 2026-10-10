/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import ortus.boxlang.modules.bxlens.util.Plain;
import ortus.boxlang.modules.bxlens.util.Secrets;
import ortus.boxlang.runtime.BoxRuntime;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.context.ScriptingRequestBoxContext;
import ortus.boxlang.runtime.interop.DynamicObject;
import ortus.boxlang.runtime.scopes.Key;
import ortus.boxlang.runtime.types.Array;
import ortus.boxlang.runtime.types.IStruct;
import ortus.boxlang.runtime.types.Struct;

/**
 * The MCP client of Lens: the MCP client of bx-ai, reached through the BoxLang class <code>models/ops/McpBridge.bx</code>. The bridge builds one
 * bx-ai client for each call with the controls Lens needs (an address guard that runs before every request, no redirects, an answer size
 * limit, the MCP handshake) and returns plain values, which this class turns into the types of {@link McpClient}.
 * <p>
 * The address guard is {@link McpUrls#check}, so the rules are the ones of the admin page, applied again immediately before every
 * connection. Nothing from bx-ai is shown as it is: a failure becomes a short reason that has no address and no secret.
 */
public final class BxAiMcpClient implements McpClient {

	private final McpUrls.Resolver	resolver;
	private final Supplier<String>	invocationPath;
	private volatile DynamicObject	bridge;
	private volatile String			bridgePath	= "";

	/**
	 * @param resolver       resolves host names (replaced in tests)
	 * @param invocationPath the class path of this module, known once the module has loaded
	 */
	public BxAiMcpClient( McpUrls.Resolver resolver, Supplier<String> invocationPath ) {
		this.resolver		= resolver == null ? McpUrls.SYSTEM : resolver;
		this.invocationPath	= invocationPath;
	}

	/** Runs {@link McpUrls#check} for the bridge. Throws the reason, which is safe to show, when the address is not allowed. */
	public static final class UrlGuard implements Predicate<String> {

		private final McpUrls.Resolver resolver;

		UrlGuard( McpUrls.Resolver resolver ) {
			this.resolver = resolver;
		}

		@Override
		public boolean test( String url ) {
			McpUrls.check( url, this.resolver );
			return true;
		}
	}

	@Override
	public List<ToolInfo> listTools( String url, Duration timeout ) throws McpException {
		Map<String, Object>	out		= run( "listTools", url, timeout );
		List<ToolInfo>		tools	= new ArrayList<>();
		for ( Object o : Plain.list( out.get( "tools" ) ) ) {
			Map<String, Object> t = Plain.map( o );
			if ( tools.size() >= MAX_TOOLS ) {
				break;
			}
			tools.add( new ToolInfo( Plain.str( t.get( "name" ) ), Plain.str( t.get( "description" ) ), Plain.map( t.get( "schema" ) ) ) );
		}
		return tools;
	}

	@Override
	public CallResult callTool( String url, String tool, Map<String, Object> args, Duration timeout ) throws McpException {
		Map<String, Object> out = run( "callTool", url, timeout, tool, bx( args ) );
		return new CallResult( Plain.str( out.get( "text" ) ), Boolean.TRUE.equals( out.get( "isError" ) ) );
	}

	// ---------------------------------------------------------------------------------------------

	private Map<String, Object> run( String method, String url, Duration timeout, Object... more ) throws McpException {
		String ok;
		try {
			ok = McpUrls.check( url, this.resolver );
		} catch ( IllegalArgumentException e ) {
			throw new McpException( "refused", e.getMessage() );
		}
		Object[] args = new Object[ 2 + more.length ];
		args[ 0 ]	= ok;
		args[ 1 ]	= timeout.toMillis();
		System.arraycopy( more, 0, args, 2, more.length );
		Object raw;
		try {
			IBoxContext ctx = new ScriptingRequestBoxContext( BoxRuntime.getInstance().getRuntimeContext() );
			// The method that gets the arguments is the one named; its first two are the address and the time limit
			raw = bridge( ctx ).dereferenceAndInvoke( ctx, Key.of( method ), args, false );
		} catch ( RuntimeException e ) {
			throw new McpException( "unreachable", "the MCP client of bx-ai failed (" + e.getClass().getSimpleName() + ")" );
		}
		Map<String, Object> out = Plain.map( Plain.plain( raw ) );
		if ( !Boolean.TRUE.equals( out.get( "ok" ) ) ) {
			String	kind	= Plain.str( out.get( "kind" ) ).equals( "refused" ) ? "refused" : "unreachable";
			String	msg		= Secrets.text( Plain.str( out.get( "message" ) ) );
			throw new McpException( kind, msg.length() > 160 ? msg.substring( 0, 160 ) + "..." : msg );
		}
		return out;
	}

	private DynamicObject bridge( IBoxContext ctx ) {
		String path = this.invocationPath.get();
		if ( path == null || path.isEmpty() ) {
			throw new IllegalStateException( "The module path is not known yet" );
		}
		DynamicObject b = this.bridge;
		if ( b == null || !path.equals( this.bridgePath ) ) {
			synchronized ( this ) {
				b = this.bridge;
				if ( b == null || !path.equals( this.bridgePath ) ) {
					DynamicObject cls = BoxRuntime.getInstance().getClassLocator().load( ctx, "bx:" + path + ".models.ops.McpBridge", List.of() );
					b				= cls.invokeConstructor( ctx, new Object[] { new UrlGuard( this.resolver ) } );
					this.bridge		= b;
					this.bridgePath	= path;
				}
			}
		}
		return b;
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
			Array a = new Array();
			for ( Object o : l ) {
				a.add( bx( o ) );
			}
			return a;
		}
		return v;
	}

}

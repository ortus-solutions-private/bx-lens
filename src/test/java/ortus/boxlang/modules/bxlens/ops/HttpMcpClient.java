/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import ortus.boxlang.modules.bxlens.util.Json;
import ortus.boxlang.modules.bxlens.util.Plain;
import ortus.boxlang.modules.bxlens.util.Secrets;

/**
 * A small MCP client over streamable HTTP for the tests: one JSON-RPC request per POST, answered as JSON or as a server sent event stream. It
 * is what Lens used before it moved to the MCP client of bx-ai, and it is kept here so the unit tests of {@link McpService} and
 * {@link Toolbox} can run against a fake server on a loopback port without a BoxLang runtime. The real path is {@link BxAiMcpClient}.
 */
public final class HttpMcpClient implements McpClient {

	private static final int		MAX_PAGES	= 5;

	/**
	 * Created on first use, never when the module loads. The JDK reads <code>jdk.httpclient.allowRestrictedHeaders</code> when its HTTP classes
	 * first load, and the BoxLang runtime sets that property when its HTTP service starts, which is after the modules are loaded. A client built
	 * earlier would freeze the list without it and break every <code>bx:http</code> call of the application that sets Content-Length or Host.
	 */
	private volatile HttpClient		http;
	private final McpUrls.Resolver	resolver;
	private final AtomicLong		ids			= new AtomicLong( 1 );

	public HttpMcpClient( McpUrls.Resolver resolver ) {
		this.resolver = resolver == null ? McpUrls.SYSTEM : resolver;
	}

	private HttpClient http() {
		HttpClient c = this.http;
		if ( c == null ) {
			synchronized ( this ) {
				c = this.http;
				if ( c == null ) {
					c			= HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NEVER ).connectTimeout( Duration.ofSeconds( 5 ) ).build();
					this.http	= c;
				}
			}
		}
		return c;
	}

	/**
	 * List the tools of a server (all pages, up to {@link #MAX_TOOLS}).
	 */
	@Override
	public List<ToolInfo> listTools( String url, Duration timeout ) throws McpException {
		Session			s		= open( url, timeout );
		List<ToolInfo>	out		= new ArrayList<>();
		String			cursor	= "";
		for ( int page = 0; page < MAX_PAGES; page++ ) {
			Map<String, Object> params = new LinkedHashMap<>();
			if ( !cursor.isEmpty() ) {
				params.put( "cursor", cursor );
			}
			Map<String, Object> result = s.request( "tools/list", params );
			for ( Object o : Plain.list( result.get( "tools" ) ) ) {
				Map<String, Object>	t		= Plain.map( o );
				String				name	= Plain.str( t.get( "name" ) );
				if ( name.isEmpty() || out.size() >= MAX_TOOLS ) {
					continue;
				}
				Map<String, Object> schema = Plain.map( t.get( "inputSchema" ) );
				out.add( new ToolInfo( name, Plain.str( t.get( "description" ) ), schema ) );
			}
			cursor = Plain.str( result.get( "nextCursor" ) );
			if ( cursor.isEmpty() ) {
				break;
			}
		}
		return out;
	}

	/**
	 * Call one tool.
	 */
	@Override
	public CallResult callTool( String url, String tool, Map<String, Object> args, Duration timeout ) throws McpException {
		Session				s		= open( url, timeout );
		Map<String, Object>	params	= new LinkedHashMap<>();
		params.put( "name", tool );
		params.put( "arguments", args );
		Map<String, Object>	result	= s.request( "tools/call", params );
		StringBuilder		sb		= new StringBuilder();
		for ( Object o : Plain.list( result.get( "content" ) ) ) {
			Map<String, Object> c = Plain.map( o );
			if ( sb.length() > 0 ) {
				sb.append( '\n' );
			}
			if ( "text".equals( Plain.str( c.get( "type" ) ) ) ) {
				sb.append( Plain.str( c.get( "text" ) ) );
			} else {
				sb.append( "[" ).append( Plain.str( c.get( "type" ) ).isEmpty() ? "content" : Plain.str( c.get( "type" ) ) ).append( " omitted]" );
			}
		}
		if ( sb.length() == 0 && result.get( "structuredContent" ) != null ) {
			sb.append( Json.write( result.get( "structuredContent" ) ) );
		}
		return new CallResult( sb.toString(), Boolean.TRUE.equals( result.get( "isError" ) ) );
	}

	// ---------------------------------------------------------------------------------------------

	private Session open( String url, Duration timeout ) throws McpException {
		String ok;
		try {
			ok = McpUrls.check( url, this.resolver );
		} catch ( IllegalArgumentException e ) {
			throw new McpException( "refused", e.getMessage() );
		}
		Session s = new Session( URI.create( ok ), timeout );
		s.handshake();
		return s;
	}

	private final class Session {

		final URI		uri;
		final Duration	timeout;
		String			sessionId	= "";

		Session( URI uri, Duration timeout ) {
			this.uri		= uri;
			this.timeout	= timeout;
		}

		/** initialize, then notifications/initialized. A server that does not know them is still used. */
		void handshake() throws McpException {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put( "protocolVersion", "2025-03-26" );
			p.put( "capabilities", Map.of() );
			p.put( "clientInfo", Map.of( "name", "bx-lens", "version", "1" ) );
			try {
				post( rpc( "initialize", p, true ), true );
				post( rpc( "notifications/initialized", Map.of(), false ), false );
			} catch ( McpException e ) {
				if ( e.kind.equals( "refused" ) || e.getMessage().startsWith( "could not connect" ) || e.getMessage().startsWith( "timed out" ) ) {
					throw e;
				}
				// A server without a handshake (the documentation servers) answers the next request anyway
			}
		}

		Map<String, Object> request( String method, Map<String, Object> params ) throws McpException {
			Map<String, Object>	msg		= rpc( method, params, true );
			Object				id		= msg.get( "id" );
			Map<String, Object>	reply	= post( msg, true );
			if ( reply == null ) {
				throw new McpException( "unreachable", "the server sent no answer" );
			}
			if ( reply.get( "error" ) != null ) {
				Map<String, Object> err = Plain.map( reply.get( "error" ) );
				throw new McpException( "unreachable", "the server refused the request: " + clip( Plain.str( err.get( "message" ) ) ) );
			}
			if ( !String.valueOf( id ).equals( String.valueOf( reply.get( "id" ) ) ) ) {
				throw new McpException( "unreachable", "the server answered another request" );
			}
			return Plain.map( reply.get( "result" ) );
		}

		private Map<String, Object> rpc( String method, Map<String, Object> params, boolean withId ) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put( "jsonrpc", "2.0" );
			if ( withId ) {
				m.put( "id", HttpMcpClient.this.ids.getAndIncrement() );
			}
			m.put( "method", method );
			m.put( "params", params );
			return m;
		}

		/** POST one message. When an answer is wanted, returns the JSON-RPC reply that carries the id of the request. */
		private Map<String, Object> post( Map<String, Object> msg, boolean wantAnswer ) throws McpException {
			HttpRequest.Builder b = HttpRequest.newBuilder( this.uri ).timeout( this.timeout ).header( "Content-Type", "application/json" )
			    .header( "Accept", "application/json, text/event-stream" ).header( "User-Agent", "bx-lens" );
			if ( !this.sessionId.isEmpty() ) {
				b.header( "Mcp-Session-Id", this.sessionId );
			}
			HttpResponse<InputStream> r;
			try {
				r = HttpMcpClient.this.http().send( b.POST( HttpRequest.BodyPublishers.ofString( Json.write( msg ) ) ).build(),
				    HttpResponse.BodyHandlers.ofInputStream() );
			} catch ( HttpTimeoutException e ) {
				throw new McpException( "unreachable", "timed out after " + this.timeout.toSeconds() + " s" );
			} catch ( java.net.ConnectException e ) {
				throw new McpException( "unreachable", "could not connect (connection refused or blocked)" );
			} catch ( java.net.UnknownHostException e ) {
				throw new McpException( "unreachable", "could not connect (host not found)" );
			} catch ( javax.net.ssl.SSLException e ) {
				throw new McpException( "unreachable", "could not connect (TLS error)" );
			} catch ( IOException e ) {
				throw new McpException( "unreachable", "could not connect (" + e.getClass().getSimpleName() + ")" );
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
				throw new McpException( "unreachable", "interrupted" );
			}
			try ( InputStream in = r.body() ) {
				String sid = r.headers().firstValue( "Mcp-Session-Id" ).orElse( "" );
				if ( !sid.isEmpty() && sid.length() <= 200 ) {
					this.sessionId = sid;
				}
				int code = r.statusCode();
				if ( code >= 300 && code < 400 ) {
					throw new McpException( "unreachable", "the server redirected (HTTP " + code + "), which is not followed" );
				}
				if ( code >= 400 ) {
					throw new McpException( "unreachable", "HTTP " + code );
				}
				String body = read( in );
				if ( !wantAnswer || body.isBlank() ) {
					return null;
				}
				String	type	= r.headers().firstValue( "Content-Type" ).orElse( "" ).toLowerCase( java.util.Locale.ROOT );
				String	want	= String.valueOf( msg.get( "id" ) );
				if ( type.contains( "text/event-stream" ) ) {
					return fromEvents( body, want );
				}
				return fromJson( body );
			} catch ( IOException e ) {
				throw new McpException( "unreachable", "the answer could not be read" );
			}
		}
	}

	private static String read( InputStream in ) throws IOException, McpException {
		ByteArrayOutputStream	out	= new ByteArrayOutputStream();
		byte[]					buf	= new byte[ 8192 ];
		int						n;
		while ( ( n = in.read( buf ) ) > 0 ) {
			out.write( buf, 0, n );
			if ( out.size() > MAX_BODY ) {
				throw new McpException( "unreachable", "the answer is larger than " + MAX_BODY + " bytes" );
			}
		}
		return out.toString( StandardCharsets.UTF_8 );
	}

	private static Map<String, Object> fromJson( String body ) throws McpException {
		try {
			Object v = Plain.parse( body.trim() );
			if ( v instanceof List<?> l && !l.isEmpty() ) {
				v = l.get( 0 );
			}
			if ( v instanceof Map ) {
				return Plain.map( v );
			}
		} catch ( RuntimeException e ) {
			// Falls through
		}
		throw new McpException( "unreachable", "the answer is not JSON" );
	}

	/** Pick the message with the wanted id out of a server sent event stream. */
	static Map<String, Object> fromEvents( String body, String wantId ) throws McpException {
		StringBuilder data = new StringBuilder();
		for ( String line : body.split( "\n", -1 ) ) {
			String l = line.endsWith( "\r" ) ? line.substring( 0, line.length() - 1 ) : line;
			if ( l.startsWith( "data:" ) ) {
				if ( data.length() > 0 ) {
					data.append( '\n' );
				}
				data.append( l.startsWith( "data: " ) ? l.substring( 6 ) : l.substring( 5 ) );
			} else if ( l.isEmpty() && data.length() > 0 ) {
				Map<String, Object> m = tryEvent( data.toString(), wantId );
				data.setLength( 0 );
				if ( m != null ) {
					return m;
				}
			}
		}
		if ( data.length() > 0 ) {
			Map<String, Object> m = tryEvent( data.toString(), wantId );
			if ( m != null ) {
				return m;
			}
		}
		throw new McpException( "unreachable", "the event stream held no answer" );
	}

	private static Map<String, Object> tryEvent( String json, String wantId ) {
		try {
			Object v = Plain.parse( json );
			if ( v instanceof Map ) {
				Map<String, Object> m = Plain.map( v );
				if ( String.valueOf( m.get( "id" ) ).equals( wantId ) ) {
					return m;
				}
			}
		} catch ( RuntimeException e ) {
			// Not an answer
		}
		return null;
	}

	private static String clip( String s ) {
		String t = Secrets.text( s );
		return t.length() > 160 ? t.substring( 0, 160 ) + "..." : t;
	}

}

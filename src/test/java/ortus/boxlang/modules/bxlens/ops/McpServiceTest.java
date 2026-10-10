/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ortus.boxlang.modules.bxlens.SettingsRegistry;
import ortus.boxlang.modules.bxlens.SettingsStore;
import ortus.boxlang.modules.bxlens.util.Json;

/**
 * The list of MCP servers: the builtin defaults, saving and loading, bad entries, the rules for adding a server, finding tools, naming them and
 * filtering them. Servers are a fake on a loopback port, names are resolved by a fake resolver.
 */
public class McpServiceTest {

	private static final McpUrls.Resolver	RESOLVER	= host -> {
															if ( host.equals( "mcp.example.com" ) ) {
																return new InetAddress[] { InetAddress.getByName( "93.184.216.34" ) };
															}
															if ( host.equals( "inside.example.com" ) ) {
																return new InetAddress[] { InetAddress.getByName( "10.0.0.9" ) };
															}
															if ( host.equals( "localhost" ) ) {
																return new InetAddress[] { InetAddress.getLoopbackAddress() };
															}
															throw new UnknownHostException( host );
														};

	@TempDir
	Path									dir;
	private final SettingsRegistry			registry	= new SettingsRegistry( List.of( "queries" ) );
	private FakeMcpServer					fake;
	private SettingsStore					store;
	private McpService						mcp;

	@BeforeEach
	void start() throws Exception {
		this.fake	= new FakeMcpServer();
		this.store	= new SettingsStore( this.dir.resolve( "o.json" ), this.registry );
		this.mcp	= new McpService( this.store, this.fake::base, RESOLVER, null, new HttpMcpClient( RESOLVER ) );
	}

	@AfterEach
	void stop() {
		this.mcp.shutdown();
		this.fake.close();
	}

	private McpService reload() {
		return new McpService( new SettingsStore( this.dir.resolve( "o.json" ), this.registry ), this.fake::base, RESOLVER, null,
		    new HttpMcpClient( RESOLVER ) );
	}

	@Test
	@DisplayName( "the default list is the eleven Ortus documentation servers, all built in, all off, every tool allowed, with the real addresses" )
	void defaults() {
		McpService		real	= new McpService( new SettingsStore( null, this.registry ), () -> "", RESOLVER, null, new HttpMcpClient( RESOLVER ) );
		List<McpServer>	all		= real.list();
		assertThat( all ).hasSize( 11 );
		assertThat( all.stream().map( McpServer::id ).toList() )
		    .containsExactly( "boxlang", "coldbox", "commandbox", "testbox", "wirebox", "logbox", "cachebox",
		        "contentbox", "qb", "quick", "cbauth" )
		    .inOrder();
		for ( McpServer s : all ) {
			assertThat( s.builtin() ).isTrue();
			assertThat( s.enabled() ).isFalse();
			assertThat( s.trusted() ).isFalse();
			assertThat( s.allowsAll() ).isTrue();
			assertThat( s.url() ).isEqualTo( "https://" + s.id() + ".ortusbooks.com/~gitbook/mcp" );
		}
		assertThat( real.problems() ).isEmpty();
		assertThat( real.tools( true ) ).isEmpty();
	}

	@Test
	@DisplayName( "a builtin server cannot be removed and its address is never taken from the file" )
	void builtinFixed() throws Exception {
		assertThat( assertThrows( IllegalArgumentException.class, () -> this.mcp.remove( "boxlang" ) ).getMessage() ).contains( "cannot be removed" );
		Files.writeString( this.dir.resolve( "o.json" ), Json.write( Map.of( "overrides", Map.of(), "mcp",
		    Map.of( "servers", List.of( Map.of( "id", "boxlang", "name", "Evil", "url", "https://evil.example.com/mcp", "builtin", true, "enabled", true,
		        "trusted", true, "allowedTools", List.of( "*" ) ) ) ) ) ) );
		McpService	again	= reload();
		McpServer	b		= again.get( "boxlang" );
		assertThat( b.url() ).isEqualTo( this.fake.base() + "/boxlang" );
		assertThat( b.name() ).isEqualTo( "BoxLang docs" );
		assertThat( b.enabled() ).isTrue();
		assertThat( b.trusted() ).isFalse();
	}

	@Test
	@DisplayName( "turning a server on or off, adding and removing are saved and come back after a restart, next to the settings overrides" )
	void roundTrip() throws Exception {
		this.store.set( Map.of( "ai.model", "mymodel" ) );
		this.mcp.enable( "boxlang", true );
		McpServer added = this.mcp.add( "Acme KB", "https://mcp.example.com/acme" );
		this.mcp.discover( "boxlang" );
		this.mcp.update( "boxlang", null, List.of( "getPage" ) );
		McpService again = reload();
		assertThat( again.get( "boxlang" ).enabled() ).isTrue();
		assertThat( again.get( "boxlang" ).allowedTools() ).containsExactly( "getPage" );
		assertThat( again.get( "coldbox" ).enabled() ).isFalse();
		assertThat( again.get( added.id() ).name() ).isEqualTo( "Acme KB" );
		assertThat( again.get( added.id() ).enabled() ).isFalse();
		assertThat( again.get( added.id() ).allowedTools() ).isEmpty();
		assertThat( again.problems() ).isEmpty();
		// The settings overrides next to it are still there, and saving settings keeps the servers
		SettingsStore s2 = new SettingsStore( this.dir.resolve( "o.json" ), this.registry );
		assertThat( s2.get().get( "ai.model" ) ).isEqualTo( "mymodel" );
		s2.set( Map.of( "ai.model", "other" ) );
		assertThat( reload().get( added.id() ) ).isNotNull();
		this.mcp.remove( added.id() );
		assertThat( reload().get( added.id() ) ).isNull();
	}

	@Test
	@DisplayName( "a bad saved entry is skipped and reported, the good ones load, and a damaged file never blocks startup" )
	void badEntries() throws Exception {
		List<Object> servers = new ArrayList<>();
		servers.add( Map.of( "id", "good", "name", "Good one", "url", "https://mcp.example.com/g", "enabled", true, "trusted", true, "allowedTools",
		    List.of( "a" ) ) );
		servers.add( Map.of( "id", "plainhttp", "name", "Plain", "url", "http://mcp.example.com/p" ) );
		servers.add( Map.of( "id", "creds", "name", "Creds", "url", "https://u:p@mcp.example.com/p" ) );
		servers.add( Map.of( "id", "noname", "name", "", "url", "https://mcp.example.com/p" ) );
		servers.add( Map.of( "id", "longname", "name", "n".repeat( 41 ), "url", "https://mcp.example.com/p" ) );
		servers.add( Map.of( "id", "Bad Id!", "name", "x", "url", "https://mcp.example.com/p" ) );
		servers.add( Map.of( "id", "coldbox", "name", "Takes a builtin id", "url", "https://mcp.example.com/p" ) );
		servers.add( Map.of( "id", "notbuiltin", "name", "Claims builtin", "url", "https://mcp.example.com/p", "builtin", true ) );
		servers.add( "just a string" );
		servers.add( Map.of( "id", "good", "name", "Duplicate id", "url", "https://mcp.example.com/d" ) );
		Files.writeString( this.dir.resolve( "o.json" ), Json.write( Map.of( "overrides", Map.of(), "mcp", Map.of( "servers", servers ) ) ) );
		McpService again = reload();
		assertThat( again.get( "good" ) ).isNotNull();
		assertThat( again.get( "good" ).enabled() ).isTrue();
		assertThat( again.get( "plainhttp" ) ).isNull();
		assertThat( again.get( "creds" ) ).isNull();
		assertThat( again.get( "noname" ) ).isNull();
		assertThat( again.get( "longname" ) ).isNull();
		assertThat( again.get( "notbuiltin" ) ).isNull();
		assertThat( again.get( "coldbox" ).builtin() ).isTrue();
		assertThat( again.get( "coldbox" ).name() ).isEqualTo( "ColdBox docs" );
		assertThat( again.list().stream().filter( s -> !s.builtin() ).count() ).isEqualTo( 1 );
		assertThat( again.problems() ).hasSize( 9 );
		assertThat( String.join( "\n", again.problems() ) ).contains( "Skipped a saved server" );
		// A damaged file
		Files.writeString( this.dir.resolve( "o.json" ), "{ this is not json" );
		McpService broken = reload();
		assertThat( broken.list() ).hasSize( 11 );
		Files.writeString( this.dir.resolve( "o.json" ), "{\"mcp\":{\"servers\":\"nope\"}}" );
		assertThat( reload().list() ).hasSize( 11 );
	}

	@Test
	@DisplayName( "adding a server enforces the name and address rules, the limit of 20, unique names and ids, and starts it off with no tool allowed" )
	void addRules() throws Exception {
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "", "https://mcp.example.com/a" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "n".repeat( 41 ), "https://mcp.example.com/a" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "ok", "http://mcp.example.com/a" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "ok", "https://inside.example.com/a" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "ok", "https://user:pw@mcp.example.com/a" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "ok", "https://169.254.169.254/latest" ) );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "ok", "https://unknown.example.com/a" ) );
		assertThat( this.mcp.list() ).hasSize( 11 );
		McpServer a = this.mcp.add( "ColdBox docs mirror", "https://mcp.example.com/a" );
		assertThat( a.id() ).isEqualTo( "coldbox-docs-mirror" );
		assertThat( a.enabled() ).isFalse();
		assertThat( a.trusted() ).isFalse();
		assertThat( a.allowedTools() ).isEmpty();
		assertThat( a.builtin() ).isFalse();
		McpServer same = this.mcp.add( "ColdBox", "https://mcp.example.com/b" );
		assertThat( same.id() ).isEqualTo( "coldbox-2" );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "coldbox docs mirror", "https://mcp.example.com/c" ) );
		for ( int i = 0; i < McpService.MAX_CUSTOM - 2; i++ ) {
			this.mcp.add( "Extra " + i, "https://mcp.example.com/e" + i );
		}
		assertThat( assertThrows( IllegalArgumentException.class, () -> this.mcp.add( "One too many", "https://mcp.example.com/z" ) ).getMessage() )
		    .contains( "20" );
	}

	@Test
	@DisplayName( "tools are found with the handshake, the session id goes back, names are namespaced and the model name has no dot" )
	void discoveryAndNaming() throws Exception {
		this.mcp.enable( "boxlang", true );
		McpService.Discovery d = this.mcp.discover( "boxlang" );
		assertThat( d.status() ).isEqualTo( "ok" );
		assertThat( this.fake.methods ).containsAtLeast( "boxlang:initialize", "boxlang:notifications/initialized", "boxlang:tools/list" ).inOrder();
		assertThat( this.fake.sessions.get( this.fake.sessions.size() - 1 ) ).isEqualTo( "s-boxlang" );
		List<McpService.Tool> tools = this.mcp.tools( true );
		assertThat( tools.stream().map( McpService.Tool::display ).toList() ).containsExactly( "boxlang.searchDocumentation", "boxlang.getPage" ).inOrder();
		assertThat( tools.stream().map( McpService.Tool::wire ).toList() ).containsExactly( "boxlang__searchDocumentation", "boxlang__getPage" ).inOrder();
		for ( McpService.Tool t : tools ) {
			assertThat( t.wire() ).doesNotContain( "." );
			assertThat( t.description() ).startsWith( "[BoxLang docs] " );
			assertThat( t.needsApproval() ).isFalse();
			assertThat( t.builtin() ).isTrue();
		}
		assertThat( McpService.wireName( "acme", "get page.v2/x" ) ).isEqualTo( "acme__get_page_v2_x" );
		assertThat( McpService.wireName( "acme", "x".repeat( 100 ) ) ).hasLength( 64 );
		assertThat( this.mcp.find( "boxlang__getPage", true ).tool() ).isEqualTo( "getPage" );
		assertThat( this.mcp.find( "boxlang.getPage", true ).tool() ).isEqualTo( "getPage" );
		assertThat( this.mcp.find( "getPage", true ) ).isNull();
		assertThat( this.mcp.find( "coldbox__getPage", true ) ).isNull();
	}

	@Test
	@DisplayName( "the schema handed to the model keeps simple types, short descriptions and the required names, and nothing else" )
	void schemaCleaned() {
		Map<String, Object>	raw		= Map.of( "type", "object", "$schema", "http://x", "additionalProperties", true, "properties",
		    Map.of( "q", Map.of( "type", "string", "description", "d".repeat( 400 ), "pattern", "(a+)+", "default", "x" ), "n", Map.of( "type", "integer" ),
		        "o", Map.of( "type", "object" ), "_hidden", Map.of( "type", "string" ) ),
		    "required", List.of( "q", "ghost" ) );
		Map<String, Object>	clean	= McpService.cleanSchema( raw );
		Map<String, Object>	props	= ( Map<String, Object> ) clean.get( "properties" );
		assertThat( props.keySet() ).containsExactly( "q", "n", "o" );
		assertThat( ( Map<String, Object> ) props.get( "q" ) ).containsKey( "description" );
		assertThat( ( ( Map<String, Object> ) props.get( "q" ) ).get( "description" ).toString().length() ).isLessThan( 310 );
		assertThat( ( Map<String, Object> ) props.get( "q" ) ).doesNotContainKey( "pattern" );
		assertThat( ( ( Map<String, Object> ) props.get( "o" ) ).get( "type" ) ).isEqualTo( "string" );
		assertThat( clean.get( "required" ) ).isEqualTo( List.of( "q" ) );
		assertThat( clean ).doesNotContainKey( "$schema" );
	}

	@Test
	@DisplayName( "only enabled servers that answered are listed, the allowed list filters the tools, a custom server has none until some are picked" )
	void filtering() throws Exception {
		McpServer custom = this.mcp.add( "Acme", "http://localhost:" + this.fake.port() + "/acme" );
		this.mcp.enable( "boxlang", true );
		this.mcp.enable( custom.id(), true );
		this.mcp.refreshStale();
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::serverId ).distinct().toList() ).containsExactly( "boxlang" );
		this.mcp.update( custom.id(), null, List.of( "getPage" ) );
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::display ).toList() )
		    .containsExactly( "boxlang.searchDocumentation", "boxlang.getPage",
		        custom.id() + ".getPage" )
		    .inOrder();
		this.mcp.update( "boxlang", null, List.of( "searchDocumentation" ) );
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::display ).toList() ).containsExactly( "boxlang.searchDocumentation",
		    custom.id() + ".getPage" ).inOrder();
		// All tools again
		this.mcp.update( "boxlang", null, List.of( "*" ) );
		assertThat( this.mcp.tools( true ) ).hasSize( 3 );
		// A viewer gets the builtin servers only
		assertThat( this.mcp.tools( false ).stream().map( McpService.Tool::serverId ).distinct().toList() ).containsExactly( "boxlang" );
		// A custom tool needs a click until the server is trusted
		assertThat( this.mcp.tools( true ).stream().filter( t -> !t.builtin() ).allMatch( McpService.Tool::needsApproval ) ).isTrue();
		this.mcp.update( custom.id(), true, null );
		assertThat( this.mcp.tools( true ).stream().noneMatch( McpService.Tool::needsApproval ) ).isTrue();
		// A tool the server does not have cannot be allowed; a builtin server cannot be marked trusted
		assertThat( assertThrows( IllegalArgumentException.class, () -> this.mcp.update( custom.id(), null, List.of( "nothing" ) ) ).getMessage() )
		    .contains( "no tool called" );
		assertThrows( IllegalArgumentException.class, () -> this.mcp.update( "boxlang", true, null ) );
		// Off means no tools
		this.mcp.enable( "boxlang", false );
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::serverId ).toList() ).doesNotContain( "boxlang" );
	}

	@Test
	@DisplayName( "the feedback tool of a documentation server is left out of all tools, and needs a click when it is ticked by name" )
	void feedbackToolIsAWrite() throws Exception {
		this.fake.toolNames = List.of( "searchDocumentation", "getPage", "sendFeedback" );
		this.mcp.enable( "boxlang", true );
		this.mcp.discover( "boxlang" );
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::tool ).toList() ).containsExactly( "searchDocumentation", "getPage" );
		this.mcp.update( "boxlang", null, List.of( "searchDocumentation", "sendFeedback" ) );
		List<McpService.Tool> tools = this.mcp.tools( true );
		assertThat( tools.stream().map( McpService.Tool::tool ).toList() ).containsExactly( "searchDocumentation", "sendFeedback" );
		assertThat( tools.get( 0 ).needsApproval() ).isFalse();
		assertThat( tools.get( 1 ).needsApproval() ).isTrue();
		Map<String, Object> row = rows( this.mcp.view( true ) ).get( "boxlang" );
		assertThat( row.get( "toolCount" ) ).isEqualTo( 3 );
		@SuppressWarnings( "unchecked" )
		List<Map<String, Object>> shown = ( List<Map<String, Object>> ) row.get( "tools" );
		assertThat( shown.get( 2 ).get( "writes" ) ).isEqualTo( true );
		assertThat( shown.get( 2 ).get( "defaultOn" ) ).isEqualTo( false );
		assertThat( shown.get( 0 ).get( "defaultOn" ) ).isEqualTo( true );
	}

	@Test
	@DisplayName( "the signature changes when the tools offered to the model change" )
	void signature() throws Exception {
		String empty = this.mcp.signature( true );
		this.mcp.enable( "boxlang", true );
		this.mcp.discover( "boxlang" );
		String one = this.mcp.signature( true );
		assertThat( one ).isNotEqualTo( empty );
		this.mcp.update( "boxlang", null, List.of( "getPage" ) );
		assertThat( this.mcp.signature( true ) ).isNotEqualTo( one );
	}

	@Test
	@DisplayName( "a server that is down, answers an error, redirects or is too slow gets a status with the reason, and does not stop the others" )
	void unreachable() throws Exception {
		McpServer dead = this.mcp.add( "Dead", "http://localhost:1/x" );
		this.mcp.enable( "boxlang", true );
		this.mcp.enable( dead.id(), true );
		this.mcp.refreshStale();
		Map<String, Object>	view	= this.mcp.view( true );
		Map<String, Object>	row		= rows( view ).get( dead.id() );
		assertThat( row.get( "status" ) ).isEqualTo( "unreachable" );
		assertThat( row.get( "reason" ).toString() ).contains( "could not connect" );
		assertThat( row.get( "reason" ).toString() ).doesNotContain( "localhost" );
		assertThat( rows( view ).get( "boxlang" ).get( "status" ) ).isEqualTo( "ok" );
		assertThat( this.mcp.tools( true ).stream().map( McpService.Tool::serverId ).distinct().toList() ).containsExactly( "boxlang" );
		this.fake.status = 503;
		assertThat( this.mcp.discover( "boxlang" ).reason() ).isEqualTo( "HTTP 503" );
		this.fake.status		= 200;
		this.fake.redirectTo	= this.fake.port();
		McpService.Discovery redirected = this.mcp.discover( "boxlang" );
		assertThat( redirected.status() ).isEqualTo( "unreachable" );
		assertThat( redirected.reason() ).contains( "redirect" );
		this.fake.redirectTo = 0;
		assertThat( this.mcp.discover( "boxlang" ).status() ).isEqualTo( "ok" );
		assertThat( rows( this.mcp.view( true ) ).get( "coldbox" ).get( "status" ) ).isEqualTo( "disabled" );
	}

	@Test
	@DisplayName( "JSON answers, servers without a handshake and long lists of tools are understood" )
	void protocolVariants() throws Exception {
		McpServer plain = this.mcp.add( "Plain", "http://localhost:" + this.fake.port() + "/plain" );
		assertThat( this.mcp.discover( plain.id() ).status() ).isEqualTo( "ok" );
		this.fake.refuseInitialize = true;
		assertThat( this.mcp.discover( "boxlang" ).status() ).isEqualTo( "ok" );
		this.fake.refuseInitialize = false;
		List<String> many = new ArrayList<>();
		for ( int i = 0; i < 150; i++ ) {
			many.add( "tool" + i );
		}
		this.fake.toolNames = many;
		assertThat( this.mcp.discover( "boxlang" ).tools() ).hasSize( McpClient.MAX_TOOLS );
	}

	@Test
	@DisplayName( "tools are looked for again only after five minutes" )
	void cache() throws Exception {
		long[]		now	= { 1_000_000L };
		McpService	m	= new McpService( new SettingsStore( null, this.registry ), this.fake::base, RESOLVER, () -> now[ 0 ], new HttpMcpClient( RESOLVER ) );
		m.enable( "boxlang", true );
		m.refreshStale();
		m.refreshStale();
		assertThat( this.fake.listCount.get() ).isEqualTo( 1 );
		now[ 0 ] += McpService.TTL_MS - 1;
		m.refreshStale();
		assertThat( this.fake.listCount.get() ).isEqualTo( 1 );
		now[ 0 ] += 2;
		m.refreshStale();
		assertThat( this.fake.listCount.get() ).isEqualTo( 2 );
		m.shutdown();
	}

	@Test
	@DisplayName( "a console session may test ten times a minute" )
	void testRate() {
		long[]		now	= { 5_000_000L };
		McpService	m	= new McpService( new SettingsStore( null, this.registry ), () -> "", RESOLVER, () -> now[ 0 ], new HttpMcpClient( RESOLVER ) );
		for ( int i = 0; i < McpService.TESTS_PER_MINUTE; i++ ) {
			assertThat( m.testAllowed( "s1" ) ).isTrue();
		}
		assertThat( m.testAllowed( "s1" ) ).isFalse();
		assertThat( m.testAllowed( "s2" ) ).isTrue();
		now[ 0 ] += 61_000;
		assertThat( m.testAllowed( "s1" ) ).isTrue();
	}

	@SuppressWarnings( "unchecked" )
	private static Map<String, Map<String, Object>> rows( Map<String, Object> view ) {
		Map<String, Map<String, Object>> out = new java.util.LinkedHashMap<>();
		for ( Object o : ( List<Object> ) view.get( "servers" ) ) {
			Map<String, Object> m = ( Map<String, Object> ) o;
			out.put( String.valueOf( m.get( "id" ) ), m );
		}
		return out;
	}

}

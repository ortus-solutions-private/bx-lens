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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ortus.boxlang.modules.bxlens.BaseIntegrationTest;
import ortus.boxlang.runtime.scopes.Key;

/**
 * The MCP client of Lens as it runs in the module: {@link BxAiMcpClient} through the BoxLang bridge on the MCP client of bx-ai, against a fake
 * MCP server on a loopback port. Names are resolved by a fake resolver, so an address can be made private without a network.
 */
public class BxAiMcpClientIntegrationTest extends BaseIntegrationTest {

	private static final McpUrls.Resolver	RESOLVER	= host -> {
															if ( host.equals( "inside.example.com" ) ) {
																return new InetAddress[] { InetAddress.getByName( "10.0.0.9" ) };
															}
															if ( host.equals( "localhost" ) || host.equals( "127.0.0.1" ) ) {
																return new InetAddress[] { InetAddress.getLoopbackAddress() };
															}
															throw new UnknownHostException( host );
														};

	private FakeMcpServer					fake;
	private BxAiMcpClient					client;

	@BeforeEach
	void start() throws Exception {
		loadBxAi();
		this.fake	= new FakeMcpServer();
		this.client	= new BxAiMcpClient( RESOLVER, () -> moduleRecord.invocationPath );
	}

	@AfterEach
	void stop() {
		this.fake.close();
	}

	@Test
	@DisplayName( "lists the tools of a server with their schemas, after the initialize handshake" )
	void lists() throws Exception {
		List<McpClient.ToolInfo> tools = this.client.listTools( this.fake.url( "acme" ), Duration.ofSeconds( 10 ) );
		assertThat( tools.stream().map( McpClient.ToolInfo::name ).toList() ).containsExactly( "searchDocumentation", "getPage" ).inOrder();
		McpClient.ToolInfo search = tools.get( 0 );
		assertThat( search.description() ).isEqualTo( "Tool searchDocumentation" );
		assertThat( search.schema().get( "required" ).toString() ).contains( "query" );
		assertThat( Map.class.cast( search.schema().get( "properties" ) ).keySet() ).containsExactly( "query", "limit", "deep" );
		assertThat( this.fake.methods ).containsAtLeast( "acme:initialize", "acme:notifications/initialized", "acme:tools/list" ).inOrder();
		// The session the server gave at initialize is sent back
		assertThat( this.fake.sessions.get( this.fake.methods.indexOf( "acme:tools/list" ) ) ).isEqualTo( "s-acme" );
	}

	@Test
	@DisplayName( "calls a tool and returns its text, for a server that answers as an event stream and for one that answers JSON" )
	void calls() throws Exception {
		McpClient.CallResult sse = this.client.callTool( this.fake.url( "acme" ), "searchDocumentation", Map.of( "query", "hello" ),
		    Duration.ofSeconds( 10 ) );
		assertThat( sse.text() ).isEqualTo( "answer from acme for hello" );
		assertThat( sse.isError() ).isFalse();
		McpClient.CallResult json = this.client.callTool( this.fake.url( "plain" ), "searchDocumentation", Map.of( "query", "again" ),
		    Duration.ofSeconds( 10 ) );
		assertThat( json.text() ).isEqualTo( "answer from plain for again" );
		assertThat( this.fake.calls ).containsExactly( "acme.searchDocumentation {query=hello}", "plain.searchDocumentation {query=again}" ).inOrder();
	}

	@Test
	@DisplayName( "an address that resolves to a private network is refused before anything is sent, with the reason of the address rules" )
	void refusesPrivate() {
		McpClient.McpException e = assertThrows( McpClient.McpException.class,
		    () -> this.client.listTools( "https://inside.example.com/mcp", Duration.ofSeconds( 10 ) ) );
		assertThat( e.kind ).isEqualTo( "refused" );
		assertThat( this.fake.methods ).isEmpty();
	}

	@Test
	@DisplayName( "the guard runs on every request, not only when the client is built: an address that changes is refused at connect time" )
	void guardRunsOnEveryRequest() throws Exception {
		// The first check (Java, before the bridge) sees a public address, the guard of the bridge sees a private one
		int[]					asked		= { 0 };
		McpUrls.Resolver		changing	= host -> {
												asked[ 0 ]++;
												return new InetAddress[] { InetAddress.getByName( asked[ 0 ] <= 1 ? "93.184.216.34" : "10.0.0.9" ) };
											};
		BxAiMcpClient			c			= new BxAiMcpClient( changing, () -> moduleRecord.invocationPath );
		McpClient.McpException	e			= assertThrows( McpClient.McpException.class,
		    () -> c.listTools( "https://rebind.example.com/mcp", Duration.ofSeconds( 10 ) ) );
		assertThat( e.kind ).isEqualTo( "refused" );
		assertThat( asked[ 0 ] ).isAtLeast( 2 );
	}

	@Test
	@DisplayName( "a redirect is an error and the new address is never called" )
	void redirect() throws Exception {
		try ( FakeMcpServer other = new FakeMcpServer() ) {
			this.fake.redirectTo = other.port();
			McpClient.McpException e = assertThrows( McpClient.McpException.class,
			    () -> this.client.listTools( this.fake.url( "acme" ), Duration.ofSeconds( 10 ) ) );
			assertThat( e.kind ).isEqualTo( "unreachable" );
			assertThat( e.getMessage() ).contains( "redirected (HTTP 302)" );
			assertThat( other.methods ).isEmpty();
		}
	}

	@Test
	@DisplayName( "an HTTP error shows its status and nothing else" )
	void httpError() {
		this.fake.status = 503;
		McpClient.McpException e = assertThrows( McpClient.McpException.class,
		    () -> this.client.callTool( this.fake.url( "acme" ), "searchDocumentation", Map.of( "query", "x" ), Duration.ofSeconds( 10 ) ) );
		assertThat( e.kind ).isEqualTo( "unreachable" );
		assertThat( e.getMessage() ).isEqualTo( "HTTP 503" );
	}

	@Test
	@DisplayName( "a server that is too slow is given up on after the time limit, in seconds, not milliseconds taken as seconds" )
	void timeout() {
		this.fake.listDelayMs = 4000;
		long					start	= System.currentTimeMillis();
		McpClient.McpException	e		= assertThrows( McpClient.McpException.class,
		    () -> this.client.listTools( this.fake.url( "acme" ), Duration.ofSeconds( 1 ) ) );
		assertThat( System.currentTimeMillis() - start ).isLessThan( 3800L );
		assertThat( e.kind ).isEqualTo( "unreachable" );
		assertThat( e.getMessage() ).isEqualTo( "timed out after 1 s" );
	}

	@Test
	@DisplayName( "a server that is not there is reported without its address" )
	void unreachable() throws Exception {
		String url = this.fake.url( "acme" );
		this.fake.close();
		McpClient.McpException e = assertThrows( McpClient.McpException.class, () -> this.client.listTools( url, Duration.ofSeconds( 5 ) ) );
		assertThat( e.kind ).isEqualTo( "unreachable" );
		assertThat( e.getMessage() ).startsWith( "could not connect" );
		assertThat( e.getMessage() ).doesNotContain( "127.0.0.1" );
		assertThat( e.getMessage() ).doesNotContain( String.valueOf( this.fake.port() ) );
		this.fake = new FakeMcpServer();
	}

	private void loadBxAi() {
		Key name = Key.of( "bxai" );
		if ( !moduleService.hasModule( name ) ) {
			var record = new ortus.boxlang.runtime.modules.ModuleRecord(
			    java.nio.file.Paths.get( "./build/modules/bx-lens/modules/bxai" ).toAbsolutePath().toString() );
			moduleService.getRegistry().put( name, record );
			record.loadDescriptor( runtime.getRuntimeContext() ).register( runtime.getRuntimeContext() ).activate( runtime.getRuntimeContext() );
		}
	}
}

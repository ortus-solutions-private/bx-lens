/**
 * [BoxLang]
 *
 * Copyright [2026] [Ortus Solutions, Corp]
 */
package ortus.boxlang.modules.bxlens.ops;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What Lens needs from an MCP client: list the tools of a server and call one. In the module this is {@link BxAiMcpClient}, which uses the MCP
 * client of bx-ai. The unit tests use a small HTTP client against a fake server instead (see the test class <code>HttpMcpClient</code>).
 * <p>
 * Whatever the client, Lens controls what it may reach: the address is checked (see {@link McpUrls}) immediately before every connection,
 * redirects are never followed, an answer is cut at {@link #MAX_BODY} characters, there are no custom headers (no credentials leave this
 * server), and every call goes through the {@link Toolbox} gate.
 */
public interface McpClient {

	/** Largest answer read from a server. */
	int	MAX_BODY	= 1_000_000;
	/** Most tools read from one server. */
	int	MAX_TOOLS	= 100;

	/** A tool a server offers. */
	record ToolInfo( String name, String description, Map<String, Object> schema ) {
	}

	/** What a tool call returned. */
	record CallResult( String text, boolean isError ) {
	}

	/** A failure with a reason that is safe to show: no address, no secret. */
	final class McpException extends Exception {

		private static final long	serialVersionUID	= 1L;
		/** unreachable (could not connect, timed out, bad answer) or refused (the address is not allowed). */
		public final String			kind;

		McpException( String kind, String message ) {
			super( message );
			this.kind = kind;
		}
	}

	/**
	 * List the tools of a server (all pages, up to {@link #MAX_TOOLS}).
	 */
	List<ToolInfo> listTools( String url, Duration timeout ) throws McpException;

	/**
	 * Call one tool.
	 */
	CallResult callTool( String url, String tool, Map<String, Object> args, Duration timeout ) throws McpException;

}

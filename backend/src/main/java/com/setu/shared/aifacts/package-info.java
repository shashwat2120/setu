/**
 * The {@link com.setu.shared.aifacts.AiFacts} contract: everything the AI advisor is allowed to learn about a student, a college or the person
 * asking, and the one thing it may write (an audit record). The advisor's module ({@code aiexplain}) holds no datasource and calls no other
 * module, so this is its only way to data. In one process it is answered by {@code aifacts.application.InProcessAiFacts}; as a service it is
 * answered over the network by the same module's {@code /ai-facts/**} endpoints, with the user's own token (see docs/contracts/ai-service.md).
 * See the parent {@code shared} package's javadoc for why contracts like this live here.
 */
package com.setu.shared.aifacts;

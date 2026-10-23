import { Fragment, memo, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { parseChatMarkdown, safeHref, type Block, type Inline } from '../lib/chatMarkdown';

function renderInline(nodes: Inline[]): ReactNode {
  return nodes.map((n, i) => {
    switch (n.t) {
      case 'text': return <Fragment key={i}>{n.v}</Fragment>;
      case 'strong': return <strong key={i}>{renderInline(n.c)}</strong>;
      case 'em': return <em key={i}>{renderInline(n.c)}</em>;
      case 'code': return <code key={i}>{n.v}</code>;
      case 'br': return <br key={i} />;
      case 'link': {
        const target = safeHref(n.href);
        if (!target) return <Fragment key={i}>{renderInline(n.c)}</Fragment>;
        return target.internal
          ? <Link key={i} to={target.href}>{renderInline(n.c)}</Link>
          : <a key={i} href={target.href} target="_blank" rel="noopener noreferrer">{renderInline(n.c)}</a>;
      }
    }
  });
}

function renderBlocks(blocks: Block[]): ReactNode {
  return blocks.map((b, i) => {
    switch (b.t) {
      case 'p': return <p key={i}>{renderInline(b.c)}</p>;
      case 'h': {
        const Tag = (b.level === 3 ? 'h4' : b.level === 4 ? 'h5' : 'h6') as 'h4' | 'h5' | 'h6';
        return <Tag key={i}>{renderInline(b.c)}</Tag>;
      }
      case 'list': {
        const items = b.items.map((item, j) => (
          <li key={j}>
            {renderInline(item.c)}
            {item.children.length > 0 && renderBlocks(item.children)}
          </li>
        ));
        return b.ordered
          ? <ol key={i} start={b.start !== 1 ? b.start : undefined}>{items}</ol>
          : <ul key={i}>{items}</ul>;
      }
      case 'table':
        return (
          <div key={i} className="chat-md-table-wrap" tabIndex={0} role="region" aria-label="Table in the advisor's answer">
            <table>
              <thead>
                <tr>
                  {b.head.map((cell, c) => <th key={c} scope="col" style={b.align[c] ? { textAlign: b.align[c]! } : undefined}>{renderInline(cell)}</th>)}
                </tr>
              </thead>
              <tbody>
                {b.rows.map((row, r) => (
                  <tr key={r}>
                    {row.map((cell, c) => <td key={c} style={b.align[c] ? { textAlign: b.align[c]! } : undefined}>{renderInline(cell)}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        );
      case 'quote': return <blockquote key={i}>{renderBlocks(b.c)}</blockquote>;
      case 'pre': return <pre key={i}><code>{b.v}</code></pre>;
      case 'hr': return <hr key={i} />;
    }
  });
}

/**
 * An advisor answer, formatted: paragraphs, bold figures, lists, tables and links, as real elements (never HTML from the model). Safe to call with text that is still
 * arriving; see {@code lib/chatMarkdown}.
 */
export const ChatMarkdown = memo(function ChatMarkdown({ text }: { text: string }) {
  return <div className="chat-md">{renderBlocks(parseChatMarkdown(text))}</div>;
});

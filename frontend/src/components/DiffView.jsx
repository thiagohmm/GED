const PREFIX = {
  ADDED: "+",
  REMOVED: "-",
  UNCHANGED: " "
};

export function DiffView({ lines, fromVersion, toVersion, onCommentLine }) {
  if (!lines) {
    return null;
  }
  return (
    <div className="diff-view">
      <div className="diff-legend">
        {fromVersion !== undefined ? `Alterações entre a versão ${fromVersion} e a versão ${toVersion}` : "Alterações"}
      </div>
      <pre className="diff-body">
        {lines.map((line, index) => (
          <div key={index} className={`diff-line diff-${line.type.toLowerCase()}`}>
            <span className="diff-sign">{PREFIX[line.type]}</span>
            <span className="diff-content">{line.content}</span>
            {onCommentLine && line.type !== "UNCHANGED" && (
              <button
                type="button"
                className="diff-comment-btn"
                title="Comentar nesta linha"
                onClick={() => onCommentLine(index, line.content)}
              >
                +
              </button>
            )}
          </div>
        ))}
      </pre>
    </div>
  );
}

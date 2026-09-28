import Figure from "./Figure";
import Reveal from "./Reveal";

export default function FacultyQuote() {
  return (
    <section className="wrap" style={{ position: "relative" }}>
      <Figure
        src="/images/lecture.jpg"
        alt="A faculty member presenting to a seated group"
        caption="Fig. 04 — Faculty view, section briefing"
        aspect="16 / 10"
      />
      <Reveal
        className="pin-note"
        style={{
          fontFamily: "var(--font-display)",
          fontStyle: "italic",
          fontSize: 15,
          lineHeight: 1.5,
        }}
      >
        &ldquo;Attendance percentage is calculated from records — never stored as one field.&rdquo;
      </Reveal>
    </section>
  );
}

import Figure from "./Figure";

export default function PhotoBreak() {
  return (
    <section className="wrap">
      <div className="photobreak">
        <Figure
          src="/images/study.jpg"
          alt="A student carrying a notebook and backpack in a classroom"
          caption="Fig. 02 — Student overview, before class"
          aspect="4 / 5"
        />
        <Figure
          src="/images/collab.jpg"
          alt="Three students reviewing work together on laptops"
          caption="Fig. 03 — Section work, cross-checked live"
          aspect="16 / 10"
        />
      </div>
    </section>
  );
}

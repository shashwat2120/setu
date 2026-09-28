import "./App.css";
import ProgressThread from "./components/ProgressThread";
import Nav from "./components/Nav";
import Hero from "./components/Hero";
import Memo from "./components/Memo";
import RoleLedger from "./components/RoleLedger";
import Spine from "./components/Spine";
import PhotoBreak from "./components/PhotoBreak";
import Tally from "./components/Tally";
import FacultyQuote from "./components/FacultyQuote";
import Finale from "./components/Finale";
import Footer from "./components/Footer";

export default function App() {
  return (
    <>
      <ProgressThread />
      <Nav />
      <Hero />
      <Memo />
      <hr className="rule wrap" />
      <RoleLedger />
      <hr className="rule wrap" />
      <Spine />
      <PhotoBreak />
      <Tally />
      <FacultyQuote />
      <Finale />
      <Footer />
    </>
  );
}

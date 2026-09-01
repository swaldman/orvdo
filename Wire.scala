package orvideo

import scala.compiletime.constValueTuple
import scala.deriving.Mirror

/** Guards against the wire drifting away from the types that model it.
  *
  * uPickle ignores unknown keys, which is what lets a partial or newly extended
  * response parse instead of failing. The cost is that a field nobody modelled
  * is discarded in total silence: the parse succeeds, the output looks normal,
  * and the field might as well not exist. Six catalog fields sat unnoticed that
  * way until something finally printed them.
  *
  * So: keep the tolerant parse, and say out loud what it threw away.
  *
  * This is opt-in per type, and deliberately so. It is right for a type meant
  * to model a payload completely, like `VideoModel` or `VideoJob`. It would be
  * useless noise on `Endpoint`, which intentionally models two fields of the
  * many that route returns.
  */
object Wire:

  /** The field names of a product type, read off the compiler's `Mirror` rather
    * than written out by hand, so adding a field cannot leave a stale answer
    * behind. Since wire types here are named for the wire, these are exactly
    * the JSON keys. */
  inline def fieldNames[A](using m: Mirror.ProductOf[A]): Set[String] =
    constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toSet

  /** Top-level keys of one JSON object that `known` does not account for.
    * Only the top level: deeper keys belong to whichever type models that
    * nesting, and would report noise from inside `usage` and `error`. */
  def unmodeled(json: ujson.Value, known: Set[String]): List[String] =
    json.objOpt.toList.flatMap(_.keys.filterNot(known)).sorted

  /** The same across a collection, answering once for the whole set rather than
    * once per element — a field added to every model in a catalog is one piece
    * of news, not twenty-seven. */
  def unmodeledAcross(jsons: Iterable[ujson.Value], known: Set[String]): List[String] =
    jsons.flatMap(unmodeled(_, known)).toList.distinct.sorted

  /** The elements of a JSON array under `key`, or nothing if the shape is not
    * what we expect — a drift check must never be the thing that fails. */
  def arrayAt(json: ujson.Value, key: String): List[ujson.Value] =
    json.objOpt.flatMap(_.get(key)).flatMap(_.arrOpt).map(_.toList).getOrElse(Nil)

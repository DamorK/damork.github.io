abstract class Token(val leftBindPower : Int, val rightBindPower : Int)
case class Literal(l : String)  extends Token(-1, -1)
case class Plus()               extends Token(10, 10)
case class Multiply()           extends Token(11, 11)
case class Power()              extends Token(13, 12)
case class LBracket()           extends Token(-1, 1)
case class RBracket()           extends Token(1, -1)

object Lexer
{
  val WhitespaceRegex = """(\s+)(.*)""".r
  val LiteralRegex = """(\d+)(.*)""".r

  def tokenize(s : String) : List[Token] = s match {
    case "" => Nil
    case _ if s.startsWith("+") =>  Plus() :: tokenize(s.tail)
    case _ if s.startsWith("**") => Power() :: tokenize(s.substring(2))
    case _ if s.startsWith("*") =>  Multiply() :: tokenize(s.tail)
    case _ if s.startsWith("(") =>  LBracket() :: tokenize(s.tail)
    case _ if s.startsWith(")") =>  RBracket() :: tokenize(s.tail)
    case LiteralRegex(value, t) =>  Literal(value) :: tokenize(t)
    case WhitespaceRegex(_, t) =>   tokenize(t)
    case _ => throw new RuntimeException("Unexpected character: " + s)
  }
}

case class Node(token : Token, children : Node*)

object Parser
{
  type Tokens = List[Token]
  def unexpected(obj : Any) = throw new RuntimeException("Unexpected " + obj.toString)
  def needs(obj : Any) = throw new RuntimeException("Missing " + obj.toString())
  def needsRight(op : Any) = needs("right argument of " + op.toString())

  def next(left : Option[Node], tokens : Tokens, rbp : Int) : (Option[Node], Tokens) =
    tokens match {
      case Nil => (left, Nil)
      case op :: _ if (op.leftBindPower >= 0 && op.leftBindPower <= rbp) => (left, tokens)
      case Literal(_) :: t if left.isEmpty => next(Some(Node(tokens.head)), t, rbp)
      case LBracket() :: t if left.isEmpty =>  {
        next(None, t, LBracket().rightBindPower) match {
          case (expr, RBracket() :: t) => next(expr, t, rbp)
          case _ => needs(RBracket())
        }
      }
      case (Plus() | Multiply() | Power()) :: t if left.isDefined => {
        val op = tokens.head
        next(None, t, op.rightBindPower) match {
          case (Some(right), t) => next(Some(Node(op, left.get, right)), t, rbp)
          case _ => needsRight(op)
        }
      }
      case token :: _ => unexpected(token)
    }

  def parse(tokens : Tokens) : Node = next(None, tokens, 0)._1.get
}

object PrattExample
{
  def eval(node : Node) : Long = node.token match {
    case Literal(l) => l.toLong
    case Plus() => node.children.map(eval).reduce{_ + _}
    case Multiply() => node.children.map(eval).reduce{_ * _}
    case Power() => node.children.map(eval).reduce{
      (l, r) => scala.math.pow(l.toDouble, r.toDouble).toLong
    }
  }

  def main(args: Array[String]): Unit = {
    args.map{Lexer.tokenize}.map{Parser.parse}.foreach{ node => println(eval(node)) }
  }
}
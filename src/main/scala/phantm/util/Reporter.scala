package phantm.util
import scala.io.Source
import phantm.Settings

class Reporter(mainFiles: List[String]) {
    type ErrorFootPrint = (String, String, String);
    type Error = (String, String, Positional, String);

    def getFootPrint(typ: String, msg: String, pos: Positional): ErrorFootPrint = {
        if (Settings.get.compactErrors) {
            // At most one error per line
            (typ, "dummy", pos.file+":"+pos.line)
        } else {
            (typ, msg, pos.file+":"+pos.line)
        }
    }

    var tickCount = 0

    def beginTicks = {
        tickCount = 0
    }

    def tick = {
        tickCount += 1
        print(".")
        if (tickCount == 80) {
            println
            tickCount = 0
        }
    }

    def endTicks = {
        if (tickCount != 0) {
            println
            tickCount = 0
        }
    }

    var errorsCount = 0
    var noticesCount = 0
    var totalErrorsCount = 0
    var totalNoticesCount = 0

    private var files = Map[String, List[String]]();
    private var errorsCheck = Map[Option[String], Set[ErrorFootPrint]]().withDefaultValue(Set[ErrorFootPrint]())
    private var errors = Map[Option[String], Set[Error]]().withDefaultValue(Set[Error]())

    def error(msg: String): Boolean = {
        errorsCount += 1;
        totalErrorsCount += 1;
        println("Error: "+ msg);
        true
    }

    def error(msg: String, pos: Positional): Boolean = {
        val error = ("Error: ", msg, pos, pos.getPos);
        val errorCheck = getFootPrint("error", msg, pos)

        if (!errorsCheck(pos.file).contains(errorCheck)) {
            errorsCheck += (pos.file -> (errorsCheck(pos.file) + errorCheck))

            errorsCount += 1;
            errors += (pos.file -> (errors(pos.file) + error))
            true
        } else {
            false
        }
    }

    def notice(msg: String): Boolean = {
        noticesCount += 1;
        totalNoticesCount += 1;
        println("Notice: "+ msg);
        true
    }

    def notice(msg: String, pos: Positional): Boolean = {
        val notice = ("Notice: ", msg, pos, pos.getPos);
        val noticeCheck = getFootPrint("notice", msg, pos)

        if (!errorsCheck(pos.file).contains(noticeCheck)) {
            errorsCheck += (pos.file -> (errorsCheck(pos.file) + noticeCheck))

            noticesCount += 1;
            errors += (pos.file -> (errors(pos.file) + notice))
            true
        } else {
            false
        }
    }


    def emitAll = {
        var errorsToDisplay = if (Settings.get.focusOnMainFiles) {
            var errorSet = Map[Option[String], Set[Error]]()
            for ((file, errs) <- errors) {
                if ((file != None) && (mainFiles contains file.get)) {
                    errorSet += (file -> errs)
                } else {
                    // decrement error counts
                    for (e <- errs) {
                        if (e._1 == "Error: ") {
                            errorsCount -= 1;
                        } else if (e._1 == "Notice: ") {
                            noticesCount -= 1;
                        }
                    }

                }
            }
            errorSet
        } else {
            errors
        }
        for (errsPerFile <- errorsToDisplay.iterator.toList.sortWith((a,b) => a._1.getOrElse("") < b._1.getOrElse("")).map(x => x._2)) {
            for ((p, msg, pos, _) <- errsPerFile.toList.sortWith{(x,y) => x._3.line < y._3.line || (x._3.line == y._3.line && x._3.col < y._3.col)}) {
                emit(p, msg, pos)
            }
        }

        clear
    }

    def clear = {
        noticesCount = 0
        errorsCount  = 0
        totalErrorsCount = 0
        totalNoticesCount = 0
        errors = errors.empty
        errorsCheck = errorsCheck.empty
    }

    private def emitNormal(prefix: String, msg: String, pos: Positional) = {
        println(pos.file.getOrElse("?")+":"+(if (pos.line < 0) "?" else pos.line)+"  "+prefix+msg)
        pos.file match {
            case Some(file) =>
                getFileLine(file, pos.line) match {
                    case Some(s) =>

                        var indent: String = ""
                        for(i <- 0 until pos.col) indent = indent + " ";

                        val size = if (pos.line == pos.line_end) {
                            if (pos.col_end > s.length) {
                                s.length - pos.col
                            } else {
                                pos.col_end - pos.col
                            }
                        } else if (pos.line < pos.line_end) {
                            s.length - pos.col
                        } else {
                            1
                        }


                        if (Settings.get.format != "termbg") {
                            println(s)

                            val (colorBegin, colorEnd) = if (Settings.get.format == "term") {
                                (Console.RED+Console.BOLD, Console.RESET)
                            } else if (Settings.get.format == "html") {
                                ("<span style=\"color: red;\">", "</span>")
                            } else {
                                ("", "")
                            }

                            if (size == 1) {
                              println(indent+colorBegin+"^"+colorEnd)
                            } else {
                              println(indent+colorBegin+(1 to size).map(i => "~").mkString+colorEnd)
                            }
                        } else {
                            print(s.substring(0, pos.col))
                            print(Console.RED_B)
                            print(s.substring(pos.col, pos.col+size))
                            print(Console.RESET)
                            println(s.substring(pos.col+size))
                            println
                        }

                    case None =>
                }
            case None =>
        }
    }

    private def emitQuickFix(prefix: String, msg: String, pos: Positional) = {
        println(pos.file.getOrElse("?")+":"+(if (pos.line < 0) "?" else pos.line)+":"+(if (pos.col < 0) "?" else pos.col+1)+"  "+prefix+msg)
    }

    private def emit(prefix: String, msg: String, pos: Positional) = {
        if (Settings.get.format == "quickfix") {
            emitQuickFix(prefix, msg, pos)
        } else {
            emitNormal(prefix, msg, pos)
        }
    }


    def getFileLine(file: String, line: Int): Option[String] = {
        val l = line-1;

        try {
            if (!files.contains(file)) {
                import java.io.{BufferedReader, FileReader}
                val input =  new BufferedReader(new FileReader(file));
                var line = "";
                var lines: List[String] = Nil;
                line = input.readLine()
                while (line != null){
                    lines = line.replaceAll("\t", " ").replaceAll("\r", "") :: lines;
                    line = input.readLine()
                }

                files += (file -> lines.reverse)
            }
            val lines = files(file)
            if (l >= lines.size || l < 0) {
                scala.Predef.error("Line out of range")
            }
            Some(lines(l))
        } catch {
            case _ =>
                None
        }
    }

}

object Reporter {
    private var rep: Option[Reporter] = None

    def get = rep.getOrElse(throw new RuntimeException("Undefined Reporter instance"))

    def set(newrep: Reporter) = this.rep = Some(newrep)

    def error(msg: String) =
        get.error(msg)

    def error(msg: String, pos: Positional) =
        get.error(msg, pos)

    def notice(msg: String) =
        get.notice(msg)

    def notice(msg: String, pos: Positional) =
        get.notice(msg, pos)
}

case class ErrorException(en: Int, nn: Int, etn: Int, ntn: Int) extends RuntimeException;
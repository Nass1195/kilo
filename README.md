# kilo — KiloChess, a chess bot in Java

A self-contained chess engine written in plain Java (no dependencies). Play it in the
terminal, or plug it into any UCI chess GUI (Arena, Cute Chess, BanksiaGUI, ...).

## Features

- **Complete rules**: legal move generation including castling, en passant, promotion,
  checkmate/stalemate, fifty-move rule, threefold repetition and insufficient material.
  Verified with standard perft test positions.
- **Search**: iterative deepening alpha-beta (principal variation search), transposition
  table (Zobrist hashing), quiescence search, null-move pruning, late move reductions,
  check extensions, MVV-LVA / killer / history move ordering, time management.
- **Evaluation**: material, piece-square tables, tapered king safety/activity,
  bishop pair, doubled/isolated/passed pawns.
- **Interfaces**: interactive terminal game (coordinate or algebraic move input) and UCI.

## Build

Requires JDK 17 or newer.

```sh
./build.sh
```

## Play in the terminal

```sh
java -jar build/chessbot.jar
```

Enter moves like `e4`, `Nf3`, `O-O`, `exd5`, or `e2e4` / `e7e8q`. Type `help` for commands
(`moves`, `undo`, `hint`, `time <secs>`, `flip`, `fen`, `setfen <fen>`, `new`, `quit`).

## Use with a chess GUI (UCI)

Register this command as an engine in your GUI:

```sh
java -jar /path/to/build/chessbot.jar uci
```

## Tests

```sh
java -cp build/classes:build/test-classes chess.PerftTest
```

## Layout

| File | Purpose |
| --- | --- |
| `src/chess/Board.java` | Board state, FEN, move generation, make/unmake, SAN, draw rules |
| `src/chess/Move.java` | Move representation |
| `src/chess/Zobrist.java` | Position hashing keys |
| `src/chess/Evaluator.java` | Static evaluation |
| `src/chess/Searcher.java` | Search engine |
| `src/chess/Main.java` | Terminal game |
| `src/chess/Uci.java` | UCI protocol |
| `test/chess/PerftTest.java` | Move generation and tactics tests |

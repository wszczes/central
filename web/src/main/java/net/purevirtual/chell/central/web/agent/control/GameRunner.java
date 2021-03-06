package net.purevirtual.chell.central.web.agent.control;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.MoveConversionException;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.github.bhlangonijr.chesslib.move.MoveGeneratorException;
import com.github.bhlangonijr.chesslib.move.MoveList;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.ejb.Asynchronous;
import javax.ejb.Stateless;
import net.purevirtual.chell.central.web.agent.entity.Game;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Stateless
@Asynchronous
public class GameRunner {

    private static final Logger logger = LoggerFactory.getLogger(GameRunner.class);

    public void run(Game game) {
        try {
            String result = runInternal(game);
            logger.info("game done, result {}", result);
            game.setResult(result);
        } catch (InterruptedException ex) {
            logger.error("Game execution was interrupted", ex);
            Thread.currentThread().interrupt();
        }
    }

    private String runInternal(Game game) throws InterruptedException {
        try {
            logger.info("reseting agents");
            long moveLimit = 500;
            Future<Void> resetW = game.getWhite().reset();
            Future<Void> resetB = game.getBlack().reset();
            resetW.get();
            resetB.get();
            logger.info("agents resetted");
            for (int i = 1; i <= 100; i++) {
                Optional<String> result = halfMove(game, game.getWhite(), moveLimit, Side.WHITE);
                if (result.isPresent()) {
                    return result.get();
                }
                result = halfMove(game, game.getBlack(), moveLimit, Side.BLACK);
                if (result.isPresent()) {
                    return result.get();
                }
            }

        } catch (ExecutionException | MoveConversionException |MoveGeneratorException ex) {
            logger.error("Game failed to complete", ex);
            return "ERROR";
        }
        logger.error("Game failed to complete - move limit reached");
        return "ERROR";
    }
    
    private Optional<String> halfMove(Game game, UciAgent agent, long moveLimit, Side side) throws InterruptedException, MoveConversionException, MoveGeneratorException {
        logger.info("{} start a move", side);
        Future<String> moveFuture = agent.move(game.getMoves(), moveLimit);
        String move;
        try {
            move = moveFuture.get(2 * moveLimit, TimeUnit.MILLISECONDS);
        } catch (TimeoutException|ExecutionException ex) {
            logger.info("{} failed to return move in allowed time", side);
            return Optional.of(side.opponent().name());
        }
        if (!isLegal(game.getMovesString(), move)) {
            logger.info("{} illegal move:  {}", side, move);
            return Optional.of(side.opponent().name());
        }
        game.getMoves().add(move);
        logger.info("all moves after {} move: {}", side, game.getMoves());
        return isDone(game.getMovesString(), side);
    }

    private Optional<String> isDone(String len, Side side) throws MoveConversionException {
        MoveList list = new MoveList();
        list.loadFromText(len);
        String fen = list.getFen();
        Board board = new Board();
        board.loadFromFen(fen);
        logger.info("fen: {}", fen);
        
        logger.info("state\n{}", board);
        logger.info("draw={}, InsufficientMaterial={}, KingAttacked={}, mated={}, stalemate={}",
                board.isDraw(), board.isInsufficientMaterial(), board.isKingAttacked(), board.isMated(), board.isStaleMate());
        if (board.isDraw() || board.isStaleMate()) {
            return Optional.of("DRAW");
        }
        if (board.isMated()) {
            logger.info("returning {}", side);
            return Optional.of(side.name());
        }
        return Optional.empty();
    }

    private boolean isLegal(String len, String move) throws MoveConversionException, MoveGeneratorException {
        
        Board board = new Board();
        if (!len.isEmpty()) {
            MoveList list = new MoveList();
            list.loadFromText(len);
            String fen = list.getFen();
            board.loadFromFen(fen);
        }
        return MoveGenerator.generateLegalMoves(board)
                .stream()
                .anyMatch(legal -> move.equals(legal.toString()));
    }
    
    enum Side {
        WHITE,
        BLACK;

        public Side opponent() {
            if (this == WHITE) {
                return BLACK;
            }
            return WHITE;
        }
    }

}

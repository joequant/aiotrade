package org.aiotrade.lib.trading

import org.aiotrade.lib.collection.ArrayList
import org.aiotrade.lib.securities.model.Sec
import org.aiotrade.lib.util.actors.Publisher
import java.util.Currency
import java.util.Locale
import java.util.UUID
import scala.collection.mutable

class Account(val description: String, protected var _balance: Double, val tradingRule: TradingRule, 
              val currency: Currency = Currency.getInstance(Locale.getDefault)
) extends Publisher {
  
  val id = UUID.randomUUID.getMostSignificantBits
  
  protected val _transactions = new ArrayList[Transaction]()
  protected val _secToPosition = new mutable.HashMap[Sec, Position]()
  
  val initialEquity = _balance
  def balance = _balance
  def credit(funds: Double) {_balance += funds}
  def debit (funds: Double) {_balance -= funds}

  def positionGainAndLoss = _secToPosition.foldRight(0.0){(x, s) => s + x._2.gainAndLoss}
  def positionEquity      = _secToPosition.foldRight(0.0){(x, s) => s + x._2.equity}
  def positionMargin      = _secToPosition.foldRight(0.0){(x, s) => s + x._2.equity * tradingRule.marginRate}

  def equity = _balance + positionEquity
  def availableFunds = equity - positionMargin
  
  def positions = _secToPosition
  def transactions = _transactions.toArray
  
  def processFilledOrder(time: Long, order: Order) {
    val expenses = order.side match {
      case OrderSide.Buy | OrderSide.SellShort => 
        tradingRule.expenseScheme.getBuyExpenses(order.filledQuantity, order.averagePrice)
      case OrderSide.Sell | OrderSide.BuyCover => 
        //val offsetGainAndLoss =
        tradingRule.expenseScheme.getSellExpenses(order.filledQuantity, order.averagePrice)
      case _ => 0.0
    }
    
    val expenseTransaction = ExpenseTransaction(time, expenses)
    val transaction = TradeTransaction(time, order, order.transactions, if (expenses != 0.0) expenseTransaction else null)
    _transactions += transaction
    
    _balance -= transaction.amount

    val quantity = order.side match {
      case OrderSide.Sell | OrderSide.SellShort => -order.filledQuantity 
      case OrderSide.Buy  | OrderSide.BuyCover  =>  order.filledQuantity
    }
    val averagePrice = order.averagePrice
    
    _secToPosition.get(order.sec) match {
      case None => 
        val position = Position(this, time, order.sec, quantity, averagePrice)
        _secToPosition(order.sec) = position
        publish(PositionOpened(this, position))
        
      case Some(position) =>
        position.add(time, quantity, averagePrice)
        if (position.quantity == 0) {
          _secToPosition -= order.sec
          publish(PositionClosed(this, position))
        } else {
          publish(PositionChanged(this, position))
        }
    }
  }
}

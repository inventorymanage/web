package inventory

import cask._
import upickle.default._
import java.sql.{Connection, DriverManager}

object Server extends cask.MainRoutes {

  // Creates a file named 'inventory.db' right in your backend folder
  val dbUrl = "jdbc:sqlite:inventory.db"

  def getConnection: Connection = DriverManager.getConnection(dbUrl)

  // Automatically build the tables when the server starts
  def initDB(): Unit = {
    val conn = getConnection
    try {
      val stmt = conn.createStatement()
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS inventory (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT UNIQUE NOT NULL,
          qty INTEGER NOT NULL,
          price REAL NOT NULL
        )
      """)
      stmt.execute("""
        CREATE TABLE IF NOT EXISTS assignments (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          emp_name TEXT NOT NULL,
          product_name TEXT NOT NULL,
          qty INTEGER NOT NULL
        )
      """)
    } finally { conn.close() }
  }

  initDB() // Run the setup

  val corsHeaders = Seq(
    "Access-Control-Allow-Origin" -> "*",
    "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" -> "Content-Type"
  )

  @cask.options("/api/inventory")
  def corsInventory() = cask.Response("", headers = corsHeaders)

  @cask.options("/api/assignments")
  def corsAssignments() = cask.Response("", headers = corsHeaders)

  @cask.get("/api/inventory")
  def getInventory() = {
    val conn = getConnection
    try {
      val rs = conn.createStatement().executeQuery("SELECT name, qty, price FROM inventory")
      var items = List[Map[String, ujson.Value]]()
      while (rs.next()) {
        items = items :+ Map(
          "name" -> ujson.Str(rs.getString("name")),
          "qty" -> ujson.Num(rs.getInt("qty")),
          "price" -> ujson.Num(rs.getDouble("price"))
        )
      }
      cask.Response(write(items), headers = corsHeaders)
    } finally { conn.close() }
  }

  @cask.postJson("/api/inventory")
  def addInventory(name: String, qty: Int, price: Double) = {
    val conn = getConnection
    try {
      val stmt = conn.prepareStatement(
        "INSERT INTO inventory (name, qty, price) VALUES (?, ?, ?) ON CONFLICT (name) DO UPDATE SET qty = inventory.qty + EXCLUDED.qty, price = inventory.price + EXCLUDED.price"
      )
      stmt.setString(1, name)
      stmt.setInt(2, qty)
      stmt.setDouble(3, price)
      stmt.executeUpdate()
      cask.Response("Success", headers = corsHeaders)
    } finally { conn.close() }
  }

  @cask.get("/api/assignments")
  def getAssignments() = {
    val conn = getConnection
    try {
      val rs = conn.createStatement().executeQuery("SELECT emp_name, product_name, qty FROM assignments")
      var items = List[Map[String, ujson.Value]]()
      while (rs.next()) {
        items = items :+ Map(
          "empName" -> ujson.Str(rs.getString("emp_name")),
          "product" -> ujson.Str(rs.getString("product_name")),
          "qty" -> ujson.Num(rs.getInt("qty"))
        )
      }
      cask.Response(write(items), headers = corsHeaders)
    } finally { conn.close() }
  }

  @cask.postJson("/api/assignments")
  def assignItem(empName: String, product: String, qty: Int) = {
    val conn = getConnection
    try {
      val updateStmt = conn.prepareStatement("UPDATE inventory SET qty = qty - ? WHERE name = ? AND qty >= ?")
      updateStmt.setInt(1, qty)
      updateStmt.setString(2, product)
      updateStmt.setInt(3, qty)
      val updated = updateStmt.executeUpdate()

      if (updated > 0) {
        val insertStmt = conn.prepareStatement("INSERT INTO assignments (emp_name, product_name, qty) VALUES (?, ?, ?)")
        insertStmt.setString(1, empName)
        insertStmt.setString(2, product)
        insertStmt.setInt(3, qty)
        insertStmt.executeUpdate()
        cask.Response("Success", headers = corsHeaders)
      } else {
        cask.Response("Not enough stock", statusCode = 400, headers = corsHeaders)
      }
    } finally { conn.close() }
  }

  override def host = "0.0.0.0"
  override def port = sys.env.getOrElse("PORT", "8080").toInt
  initialize()
}
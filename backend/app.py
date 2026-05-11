import os
import sqlite3

from flask import Flask, request, jsonify
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)

DB_PATH = os.getenv("DB_PATH", "fall_detector.db")


def _get_db_connection():
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def _init_db():
    with _get_db_connection() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL
            )
            """
        )


_init_db()


def _get_credentials(payload):
    if not payload:
        return None, None
    username = payload.get("username")
    password = payload.get("password")
    return username, password


@app.route('/register', methods=['POST'])
def register_user():
    username, password = _get_credentials(request.json)
    if not username or not password:
        return jsonify({"error": "Missing username or password"}), 400

    password_hash = generate_password_hash(password)
    try:
        with _get_db_connection() as connection:
            connection.execute(
                "INSERT INTO users (username, password_hash) VALUES (?, ?)",
                (username, password_hash)
            )
    except sqlite3.IntegrityError:
        return jsonify({"error": "Username already exists"}), 409

    return jsonify({"status": "success", "message": "User registered"}), 201


@app.route('/login', methods=['POST'])
def login_user():
    username, password = _get_credentials(request.json)
    if not username or not password:
        return jsonify({"error": "Missing username or password"}), 400

    with _get_db_connection() as connection:
        row = connection.execute(
            "SELECT password_hash FROM users WHERE username = ?",
            (username,)
        ).fetchone()

    if not row or not check_password_hash(row["password_hash"], password):
        return jsonify({"error": "Invalid credentials"}), 401

    return jsonify({"status": "success", "message": "Login ok"}), 200

@app.route('/location', methods=['POST'])
def receive_location():
    data = request.json
    if not data:
        return jsonify({"error": "No data provided"}), 400
    
    lat = data.get('latitude')
    lon = data.get('longitude')
    mode = data.get('mode', 'Unknown')
    accuracy = data.get('accuracy', 'Unknown')
    freshness = data.get('freshness', 'Unknown')
    
    print(f"Received position - Lat: {lat}, Lon: {lon}, Mode: {mode}, Accuracy: {accuracy}m, Freshness: {freshness}ms")
    
    return jsonify({"status": "success", "message": "Location received"}), 200

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)

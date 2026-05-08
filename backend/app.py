from flask import Flask, request, jsonify

app = Flask(__name__)

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

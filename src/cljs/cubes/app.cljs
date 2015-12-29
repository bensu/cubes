(ns cubes.app
  (:require [clojure.set :as set]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [quil.core :as q :include-macros true]
            [datascript.core :as d]))

(enable-console-print!)

;; ======================================================================
;; Constants

(def grid-size [600 600])

(def frame-rate 20)

;; ======================================================================
;; Squares

(defn rand-rgb []
  {:r (rand-int 255) :g (rand-int 255) :b (rand-int 255)})

(defn sq->rgb [sq]
  [(:r sq) (:g sq) (:b sq)])

;; TODO: take x as arg to decouple from grid-size
(defn rand-square []
  (let [side 40]
    (merge {:side side :y 0 :x (rand-int (- (first grid-size) side))}
           (rand-rgb))))

(defn overlap?
  "Checks if two integer intervals overlap"
  [[a-min a-max] [b-min b-max]]
  (and (< a-min b-max) (< b-min a-max)))

(defn sq->interval
  "Gets the x interval the square occupies on the screen"
  [{:keys [x side]}]
  [x (+ x side)])

(defn sq-overlap?
  "Checks if two squares' x coordinates overlap"
  [a b]
  (overlap? (sq->interval a) (sq->interval b)))

(defn sq->top
  "Gets the y coordinate for the squares top side"
  [{:keys [y side]}]
  (+ y side))

(defn sq-clear?
  "Checks if the sq is clear (if it's not supporting any other squares)"
  [db sq-id]
  (empty? (d/q '[:find ?supports :in $ ?id
                 :where [?id :supports ?supports]]
               db
               sq-id)))

(defn dist [a b]
  (letfn [(d [k]
            (Math/pow (- (get a k) (get b k)) 2))]
    (Math/sqrt (+ (d :x) (d :y)))))

;; http://clj-me.blogspot.com.uy/2009/06/linear-interpolation-and-sorted-map.html
(defn interpolator
  "Takes a coll of 2D points (vectors) and returns
  their linear interpolation function."
  [points]
  (let [m (into (sorted-map) points)]
    (fn [x]
      (assert (number? x))
      (let [[[x1 y1]] (rsubseq m <= x)
            [[x2 y2]] (subseq m > x)]
        (let [out (if x2
                    (+ y1 (* (- x x1) (/ (- y2 y1) (- x2 x1))))
                    y1)]
          (assert (not (js/isNaN out)) [x1 x2 y1 y2 x])
          out)))))

(defn path-through
  "Returns a path through all the sqs"
  [& sqs]
  (let [d (map dist (drop 1 sqs) sqs)
        total-d (apply + d)
        w (map #(/ % total-d) d)
        inc-w (reduce (fn [acc v] (conj acc (+ v (last acc)))) [0] w)]
    (juxt (interpolator (map vector inc-w (map :x sqs)))
          (interpolator (map vector inc-w (map :y sqs))))))

(defn rect-path
  "Returns an arc-like path to the target"
  [sq target-sq]
  (let [sq-i (if (< (:y sq) (:y target-sq))
               (assoc sq :y (+ (:side target-sq) (:y target-sq)))
               (update sq :y (partial + (:side sq))))]
    (path-through sq sq-i (assoc sq-i :x (:x target-sq)) target-sq)))

(defn sq->center
  [{:keys [x y side]}]
  [(+ x (/ side 4)) (+ y (/ side 1.5))])

(defn ent->map [ent]
  (merge {:db/id (:db/id ent)} ent))

(defn get-sq [db id]
  {:pre [(number? id)]}
  (ent->map (d/entity db id)))

(defn db->squares
  "Coll with all the squares in db"
  [db]
  (->> (d/q '[:find ?id :where [?id :x _]] db)
       (map first)
       (map (partial get-sq db))))

(defn find-support
  "Returns the support for an x position, or nil if none is found"
  [db sq]
  (some->> (db->squares db)
           (filter (partial sq-overlap? sq))
           (apply max-key sq->top)))

(def on-top '[[[on-top ?a ?b]
               [?b :supports ?a]]
              [[on-top ?a ?b]
               [?b :supports ?x]
               [on-top ?a ?x]]])

(defn sq-supports [db sq]
  (map first (d/q '[:find ?id :in $ % ?sq
                    :where [on-top ?id ?sq]]
                  db on-top sq)))

(defn supported-by
  "All the squares that support sq, where y-sqs is indexed by sq->top"
  [db sq-id]
  (->> sq-id
       (d/q '[:find ?id :in $ ?sq :where [?id :supports ?sq]] db)
       (map first)))

(defn support-pairs
  "All the support pairs in the indexed stacked-squares"
  [db]
  (->> (db->squares db)
       (mapcat (fn [sq]
                 (map (partial vector (:db/id sq))
                      (supported-by db (:db/id sq)))))))

(defn clear-sqs
  "All the sqs which are not supporting other sqs"
  [db]
  (let [all-sqs (set (map :db/id (db->squares db)))
        support-sqs (->> db
                         (d/q '[:find ?id :where [?id :supports _]])
                         (map first)
                         set)]
    (set/difference all-sqs support-sqs)))

(defn stack-tx
  "Tx data to stack sq on top of target-sq"
  [sq target-sq]
  [[:db/add (:db/id target-sq) :supports (:db/id sq)]
   [:db/add (:db/id sq) :y (sq->top target-sq)]
   [:db/add (:db/id sq) :x (:x target-sq)]])

(defn unsupport-tx
  "Tx data to clear sq from all of its supports"
  [db sq]
  (mapv (fn [id] [:db/retract id :supports sq])
        (supported-by db sq)))

(defmulti op->tx
  "Transforms an operation into the necessary tx data to be applied to db"
  (fn [_ op] (:type op)))

(defmethod op->tx :default [_ _] [])

(defmethod op->tx :move
  [db {:keys [move to]}]
  (concat (unsupport-tx db move)
          (stack-tx (get-sq db move) (get-sq db to))))

(defn find-clear-space
  "Coordinate that has an adjacent clear space (length: width)"
  [db width]
  (let [l (first grid-size)
        base-sqs (->> db
                      (d/q '[:find (pull ?i [:db/id :side :x])
                             :where [?i :y 0]])
                      (mapcat identity))]
    (->> base-sqs
         (map #(+ (:side %) (:x %)))
         (concat [0])
         (filter (fn [x]
                   (every? #(not (sq-overlap? {:x x :side width} %)) base-sqs)))
         first)))

(defmethod op->tx :clear
  [db {:keys [move]}]
  (let [sq (get-sq db move)]
    (cond
      (zero? (:y sq)) []
      :else (concat (unsupport-tx db move)
                    [[:db/add move :y 0]
                     [:db/add move :x (find-clear-space db (:side sq))]]))))

(defn apply-op!
  "Returns the squares after the operation is applied"
  [conn {:keys [move to] :as op}]
  (d/transact conn (op->tx @conn op)))

(defn step-op
  "Returns a new database as if the operation was applied"
  [db op]
  (d/db-with db (op->tx db op)))

(defn stack-sq
  "Stacks the new sq on top of the squares"
  [db sq]
  (d/db-with db (let [temp-id -1
                      sq' (assoc sq :db/id temp-id)]
                  (concat [sq']
                          (if-let [support-sq (find-support db sq)]
                            (stack-tx sq' support-sq)
                            [[:db/add temp-id :y 0]])))))

(defn stack-squares
  "Add n stacked squares to db"
  [db n]
  (loop [db db
         sqs (map (fn [_] (rand-square)) (range n))]
    (if-let [sq (first sqs)]
      (recur (stack-sq db sq) (rest sqs))
      db)))

(defmulti valid-op? (fn [_ op] (:type op)))

(defmethod valid-op? :default [_ _] true)

(defn sq-exist? [db id]
  (some? (d/entity db id)))

(defn sqs-exist? [db {:keys [move to]}]
  (and (sq-exist? db move)  (sq-exist? db to)))

(defmethod valid-op? :clear [db op]
  (let [sq (get-sq db (:move op))]
    (and (some? sq) (some? (find-clear-space db (:side sq))))))

(defmethod valid-op? :claw [db op]
  (sqs-exist? db op))

(defmethod valid-op? :move [db {:keys [move to] :as op}]
  (and (sqs-exist? db op) (sq-clear? db move) (sq-clear? db to)))


;; ======================================================================
;; Planning

(defn maybe-step-op
  "Step-op that checks if the op is valid, returns nil if not"
  [db op]
  (when (and (some? db) (valid-op? db op))
    (step-op db op)))

(defn valid-plan?
  "Checks if the plan is valid by applying all the ops"
  [db plan]
  (some? (reduce maybe-step-op db plan)))

(defn done?
  "Is the goal achieved in the db?"
  [[sq tsq] db]
  (contains? (d/q '[:find ?s :in $ ?tsq
                    :where [?tsq :supports ?s]]
                  db tsq)
             [sq]))

(defn possible-ops
  "All possible future ops for a determined db"
  [db]
  (let [c-sqs (clear-sqs db)]
    (->> (for [x c-sqs y c-sqs]
           (when-not (= x y) [x y]))
         (remove nil?)
         (map (fn [[x y]] {:type :move :move x :to y}))
         (concat (map (fn [sq] {:type :clear :move sq}) c-sqs)))))

(defn distance [[sq tsq] db]
  (if (done? [sq tsq] db)
    (- js/Infinity)
    (+ (count (sq-supports db sq)) (count (sq-supports db tsq)))))

(defn plan-moves [goal db]
  (loop [db db plan [] n 0]
    (cond
      (done? goal db) plan
      (= 100 n) [:not-found plan]
      :else (let [ops (->> (possible-ops db)
                           (map (juxt identity (partial step-op db)))
                           (sort-by (comp (partial distance goal) second)))]
              (let [[op db'] (first ops)]
                (recur db' (conj plan op) (inc n)))))))

;; ======================================================================
;; Quil Helpers

(defn clear-canvas!
  "Draws a grey rectangle covering the whole canvas"
  []
  (q/fill 192 192 192)
  (apply q/rect 0 0 grid-size))

(defn xy->xy
  "Coordinate transformation from regular cartesian to screen cartesian:
   (x', y') = (x, H - y)"
  [sq]
  (update sq :y #(- (first grid-size) (:side sq) %)))

(defn sq-text!
  "Paints text inside the square"
  [sq]
  (q/stroke 0 0 0)
  (q/fill 0 0 0)
  (let [[x y] (sq->center sq)]
    (q/text (:db/id sq) x y)))

(defn square!
  "Draws a square in the screen"
  [sq]
  (let [{:keys [x y side] :as sq'} (xy->xy sq)]
    (apply q/fill (sq->rgb sq'))
    (q/rect x y side side)
    (sq-text! sq')))

(defn claw!
  "Draws a claw at x up to y"
  [x y]
  (q/fill 0 0 0)
  (q/rect (- x 10) (- y 5) 25 5)
  (q/rect x 0 5 y))

(defn grip!
  "Draws a claw to a square"
  [sq]
  (let [{:keys [x y side]} (xy->xy sq)]
    (claw! (+ x (/ side 2)) y)))

;; ======================================================================
;; Render State

(defonce app-state
  (atom {:db0 nil
         :plan []
         :db nil
         :ops []
         :frame 0}))

(defmulti state->render
  "Takes the state, an op, the current frame,
  and returns what is necessary for rendering: claw and squares"
  (fn [_ op _] (:type op)))

(defmethod state->render :default [db _ _]
  {:squares (db->squares db)})

(defn sq->target [sq target-sq f]
  (let [target-sq' (assoc target-sq :y (sq->top target-sq))
        [x y] ((rect-path sq target-sq') f)]
    (assoc sq :x x :y y)))

(defn move-sq [db op f]
  (sq->target (get-sq db (:move op)) (get-sq db (:to op)) f))

(defmethod state->render :claw [db op f]
  {:claw (move-sq db op f)
   :squares (db->squares db)})

(defmethod state->render :move [db op f]
  (let [{:keys [x y db/id] :as sq'} (move-sq db op f)]
    {:claw sq'
     :squares (db->squares (d/db-with db [[:db/add id :x x]
                                          [:db/add id :y y]]))}))

(defmethod state->render :clear [db op f]
  (let [sq (get-sq db (:move op))
        fake-sq {:side (:side sq) :y 0 :x (find-clear-space db (:side sq))}
        {:keys [x y db/id] :as sq'} (sq->target sq fake-sq f)]
    {:claw sq'
     :squares (db->squares (d/db-with db [[:db/add id :x x]
                                          [:db/add id :y y]]))}))

(defn add-claw-moves
  "Add intermediate claw moves to the plan (for rendering)"
  [plan]
  (letfn [(add-claw-move [prev {:keys [move]}]
            (let [to (:move prev)]
              (cond-> [prev]
                (not= to move) (conj {:type :claw :to move :move to}))))]
    (->> (drop 1 (cycle plan))
         (mapcat add-claw-move plan))))

;; ======================================================================
;; Initialize and Render

(defn reset-state [s]
  (assoc s :db (:db0 s) :ops (:plan s) :frame 0))

(defn setup! []
  (q/frame-rate frame-rate)
  (q/color-mode :rgb)
  (q/background 200)
  (let [schema {:supports {:db/cardinality :db.cardinality/many
                           :db/valueType :db.type/ref}}
        db (stack-squares (d/empty-db schema) 10)
        goal [4 5]
        plan (plan-moves goal db)
        plan (if (valid-plan? db plan)
               (add-claw-moves plan)
               [])]
    (swap! app-state
           #(assoc % :db0 db :plan plan :goal goal
                   :db db :frame 0 :ops plan))))

(defn step-frame
  "If there are any operations left it steps the state one frame"
  [s]
  (if-let [op (first (:ops s))]
    (if (> 1 (:frame s))
      (assoc s :frame (+ (:frame s) (/ 1 frame-rate)))
      (cond-> s
        true (assoc :frame 0 :ops (rest (:ops s)))
        (some? op) (update :db #(step-op % op))))
    s))

(defn draw! []
  (clear-canvas!)
  (let [{:keys [db ops frame]} @app-state
        op (first ops)
        {:keys [squares claw]} (state->render db op frame)]
    (doseq [sq squares]
      (square! sq))
    (when (and (:x claw) (:y claw))
      (grip! claw))
    (swap! app-state step-frame)))

(q/defsketch cubes
  :title "Oh so many grey circles"
  :host "canvas"
  :settings #(q/smooth 2) ;; Turn on anti-aliasing
  :setup setup!
  :draw draw!
  :size grid-size)

;; ======================================================================
;; DOM Setup

(defn widget [data owner]
  (reify
    om/IRender
    (render [this]
      (dom/div nil ""))))

(defn init []
  (om/root widget {:text "Hello world!"}
           {:target (. js/document (getElementById "container"))}))
